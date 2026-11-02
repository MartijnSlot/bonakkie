# bonakkie

A backend for one deal of the Twents card game Bonak, served over an HTTP API. It covers:

    Dealing → Bidding → Stok exchange → Trump selection → Final roem → Ready to play

It stops at "Ready to play" (or at an immediate contract failure). Trick play and scoring hands are
not implemented, and there is no frontend yet.

## Phases

1. **Dealing.** Bonak is played with 3 to 5 players. The full 32-card deck (7 to Ace in four suits)
   is shuffled. Each player gets 6 cards and the stok gets 2: one face up, one face down. The rest
   are set aside: 12 cards with 3 players, 6 with 4, none with 5.
2. **Bidding.** See the bidding rules below. Two outcomes cause a fresh deal, using the full
   reshuffled deck with hands, stok, discards and auction cleared (`dealNumber` goes up):
   - **Everyone says Rond on the opening circuit:** the same dealer deals again.
   - **Everyone passes without a bid:** the deal passes to the next player clockwise. This
     includes auctions where some players said Rond first and then everyone passed.
3. **Stok exchange.** Taking the stok is mandatory. The declarer receives both stok cards
   automatically, holds 8, and discards exactly 2 different cards from their hand. They may
   discard the cards they just took. With several Praat players, the exchange goes in the order
   they first bid Praat, not in seating order. Each player's 2 discards become the next player's
   stok, and the last player's discards are set aside.
4. **Trump selection.** After the exchange, the declarer chooses trumps. That applies to points
   contracts, plain Bonak, Bonak with roem, and a single Praat player. With several Praat
   players, the hand is played without trumps and this step is skipped. Trumps are never inferred
   from any card.
5. **Final roem.** Roem only applies to the declarer who won the bidding. Other players' roem is
   never calculated, and opponents can't cancel or beat it. It comes from the declarer's 6 cards
   after discarding, with the chosen trumps:
   - **Points contracts:** the actual roem (capped at 100) lowers the points needed from tricks:
     `requiredTrickPoints = max(bid - roem, 0)`, with `minimumTricks = 1`. The bid stays as it was.
   - **Bonak with roem:** the declared roem is a minimum. If the actual roem is lower, the contract
     fails immediately (`CONTRACT_FAILED`, reason `INSUFFICIENT_BONAK_ROEM`) and never becomes ready
     to play. If it is equal or higher, the commitment is met and the bid stays as declared. No
     penalty or score change is recorded beyond the failure.
   - **Plain Bonak and Praat:** roem isn't calculated.
6. **Ready to play.** Every player holds 6 cards. No more actions are accepted.

## Bidding rules

- Bidding starts left of the dealer and goes clockwise, skipping players who passed.
- **Pass** is permanent for the auction.
- **Rond** skips a turn and keeps the right to bid later. It is only allowed while every earlier
  action in the auction was Rond, so any Pass ends it.
- **Points** bids run from 50 to 125 in steps of 5. Each bid must be higher than the last, and a
  bid over Praat must be at least 100. The bid includes roem.
- **Praat** (take no tricks) can be bid before any points bid, or over a points bid of 75 or less.
  Other players may join it. Bonak or a points bid of 100 or more beats it.
- **Bonak** (take every trick with trumps) beats any points bid and Praat. Bonak with roem beats
  plain Bonak. After that, an overbid must declare strictly more roem. Equal roem never wins, and
  which combination the roem comes from doesn't matter. Declared roem must be a total a final
  6-card hand can actually hold: 20, 40, 50, 60, 70 or 100. A test checks every possible 6-card
  hand, and 30, 80 and 90 never occur.

## Roem

| Combination | Roem |
|---|---:|
| Three consecutive cards of one suit | 20 |
| King and Queen of trumps (stuk) | 20 |
| Three consecutive cards including stuk | 40 |
| Four consecutive cards of one suit | 50 |
| Four consecutive cards including stuk | 70 |
| Five or six consecutive cards of one suit | 100 |
| Four Aces, Kings, Queens, Jacks or Tens | 100 each |

- Sequences are scored whole. Shorter sequences inside them don't score separately.
- A card scores in at most one combination. The exception is the trump King and Queen, which
  also count for stuk. Stuk is counted once.
- The total is capped at 100.
- Sometimes a hand can be scored in more than one way. For example, four Jacks plus 10♥ and Q♥
  gives either four Jacks (100) or 10-J-Q of hearts (20). The highest total counts. The calculator
  still lists every alternative.

## Not built yet

- **Checking roem.** Only the declarer sees their final roem. Other players will check it during
  the play phase, which isn't built yet.

## Visibility

The server decides what each response contains:

- **Public state** (`GET /api/round`): phase, turn, bidding, exchange progress, who must choose
  trumps, the chosen trumps, hand sizes, counts of stok, unused and discarded cards, and any
  contract failure with its reason and declared roem. The only card it names is the original
  face-up stok card, which everyone saw during bidding. It never says where that card ended up.
- **A player's view** (`GET /api/round/players/{name}`): the public state plus that player's own
  hand, the cards they received in the exchange, their own discards, whether they may choose
  trumps now, and their final roem if they are the declarer.
- The face-down stok card, other players' hands, unused cards, discards and the declarer's actual
  roem are never sent to anyone else.

There is no authentication yet (planned for later), so anyone who can reach the API can request
any player's view.

## Code layout

- `src/main/kotlin/round`: the round (dealing, stok exchange, trumps, final roem, card locations)
- `src/main/kotlin/auction`: the bidding auction
- `src/main/kotlin/roem`: the roem calculator
- `src/main/kotlin/api`: the HTTP resources
- `src/main/kotlin/web`: serving the single page app, and CORS

## Running

Kotlin, Dropwizard, JDK 25. The server listens on http://localhost:6969:

```
./gradlew run
./gradlew test
```

## Single page app

The backend is ready for a single page app (there is none yet). Both are configured in
`config/config.yml` under `frontend`:

- **`directory`:** a folder with the built app (`index.html` plus assets), served at `/`. Paths
  that aren't files get `index.html`, so client-side routes like `/table/3` survive a page refresh.
  A missing asset such as `/assets/x.js` is still a 404, and files outside the folder are never
  served. Leave it empty to serve only the API.
- **`allowedOrigins`:** browser origins allowed to call the API from elsewhere (CORS), such as a
  dev server on `http://localhost:5173`. This isn't needed when the app is served by this server,
  or when the dev server proxies `/api` to `http://localhost:6969`.

The API is always under `/api`, so the app owns every other path.

### Building a client

- **Rendering a player's screen:** `GET /api/round/players/{name}` gives everything for one
  player. `availableActions` lists exactly what they can do now: legal bids, how many cards to
  discard, or which trump suits to choose from. The client doesn't need to know any rules.
- **Polling for other players' moves:** every response for `GET /api/round` and
  `GET /api/round/players/{name}` carries an `ETag` (the round version). Send it back as
  `If-None-Match`, and the server answers `304 Not Modified` with no body until something changes.
- **Sending moves:** include the `version` you last saw. If someone else moved first, you get `409`;
  reload the state and try again.
- **Errors** are always JSON: `{"code": ..., "message": "..."}`. The message is meant for players.

## API

| Method | Path | Body |
|---|---|---|
| `POST` | `/api/round` | `{"players": [...], "dealer": "..."}`: deals a fresh round and starts bidding |
| `GET` | `/api/round` | public state, including `version` and the auction's `legalActions` |
| `GET` | `/api/round/players/{player}` | that player's private view, including `availableActions` |
| `POST` | `/api/round/bids` | `{"player": "...", "type": "BID_POINTS", "amount": 80, "version": 3}` |
| `POST` | `/api/round/discards` | `{"player": "...", "cards": [{"rank": "SEVEN", "suit": "CLUBS"}, ...], "version": 7}` |
| `POST` | `/api/round/trump` | `{"player": "...", "suit": "HEARTS", "version": 8}` |
| `GET` | `/api/roem/combinations` | roem table |
| `POST` | `/api/roem/calculate` | `{"trump": "HEARTS", "cards": [...]}`: every scoring alternative, and the highest total |

- Every bid, discard and trump choice must include the `version` from the latest state.
- A request with an older version gets `409`, for example a repeated submission or a request
  from an earlier round. Versions keep counting up across new rounds, so they never repeat.
- A request that breaks a rule gets `400` with `{"code": 400, "message": "..."}`.
- In both cases the round is left unchanged.
- The round is kept in memory, so there is one round per server and it is lost on restart.
