# bonakkie

A backend for one deal of the Twents card game Bonak, served over an HTTP API. It covers:

    Dealing → Bidding → Stok exchange → Final roem determination → Ready to play

It stops at "Ready to play". Trick play, trump selection, and scoring hands are not implemented,
and there is no frontend yet.

## Phases

1. **Dealing.** The 32-card deck (7 to Ace in four suits) is shuffled. Each player gets 6 cards and
   the stok gets 2: one face up, one face down. The remaining cards are set aside. With 6 cards
   each plus the stok, the deck allows 2 to 5 players.
2. **Bidding.** See the bidding rules below. If every player says Rond on the opening circuit, the
   cards are dealt again (`dealNumber` goes up) and there is no stok exchange. If every player
   passes without a bid, the round ends in `BIDDING_UNRESOLVED`, because the rules don't define
   that outcome. After an all-Rond redeal, the same dealer deals again.
3. **Stok exchange.** Taking the stok is mandatory. The declarer receives both stok cards
   automatically, holds 8, and discards exactly 2 different cards from their hand. They may
   discard the cards they just took. With several Praat players, the exchange goes in the order
   they first bid Praat, not in seating order. Each player's 2 discards become the next player's
   stok, and the last player's discards are set aside.
4. **Final roem.** Each declarer's roem is calculated from the 6 cards they keep after their own
   discard. Trump selection isn't specified yet, so roem is given for every possible trump suit.
   That only matters for stuk. Points contracts also get `requiredTrickPoints = max(bid - roem, 0)`
   and `minimumTricks = 1`. For Bonak with roem, the roem committed during bidding is kept
   alongside the actual final roem.
5. **Ready to play.** Every player holds 6 cards. No more actions are accepted.

## Bidding rules

- Bidding starts left of the dealer and goes clockwise, skipping players who passed.
- **Pass** is permanent for the auction.
- **Rond** skips a turn and keeps the right to bid later. It is only allowed while every earlier
  action in the auction was Rond.
- **Points** bids run from 50 to 125 in steps of 5. Each bid must be higher than the last, and a
  bid over Praat must be at least 100. The bid includes roem.
- **Praat** (take no tricks) can be bid before any points bid, or over a points bid of 75 or less.
  Other players may join it. Bonak or a points bid of 100 or more beats it.
- **Bonak** (take every trick with trumps) beats any points bid and Praat. After a Bonak, only
  Bonak with more roem can outbid it. That roem must be one of 20, 40, 50, 60, 70, 80, 90 or 100.
- **Roem**: sequences of 3, 4 and 5-6 cards score 20, 50 and 100. Stuk (trump King and Queen) adds
  20, so a sequence with stuk scores 40 or 70. Four Aces, Kings, Queens, Jacks or Tens score 100
  each. Combinations add up, may share cards, and the total is capped at 100.

## Visibility

The server decides what each response contains:

- **Public state** (`GET /api/round`): phase, turn, bidding, exchange progress, hand sizes, and
  counts of stok, unused and discarded cards. The only card it names is the original face-up stok
  card, which everyone saw during bidding. It never says where that card ended up.
- **A player's view** (`GET /api/round/players/{name}`): the public state plus that player's own
  hand, the cards they received in the exchange, their own discards, and their final roem if they
  are a declarer. Only the declarer sees their final roem. The other players will check it during
  the play phase, which isn't built yet.
- The face-down stok card, other players' hands, unused cards, and discards are never sent to
  anyone else.

There is no authentication yet (planned for later), so anyone who can reach the API can request
any player's view.

## Code layout

- `src/main/kotlin/round`: the round (dealing, stok exchange, final roem, card locations)
- `src/main/kotlin/auction`: the bidding auction
- `src/main/kotlin/roem`: the roem calculator
- `src/main/kotlin/api`: the HTTP resources

## Running

Kotlin, Dropwizard, JDK 25. The server listens on http://localhost:6969:

```
./gradlew run
./gradlew test
```

## API

| Method | Path | Body |
|---|---|---|
| `POST` | `/api/round` | `{"players": [...], "dealer": "..."}`: deals a fresh round and starts bidding |
| `GET` | `/api/round` | public state, including the auction's `legalActions` |
| `GET` | `/api/round/players/{player}` | that player's private view |
| `POST` | `/api/round/bids` | `{"player": "...", "type": "BID_POINTS", "amount": 80}` |
| `POST` | `/api/round/discards` | `{"player": "...", "cards": [{"rank": "SEVEN", "suit": "CLUBS"}, ...]}` |
| `GET` | `/api/roem/combinations` | roem table |
| `POST` | `/api/roem/calculate` | `{"trump": "HEARTS", "cards": [...]}` |

Rejected actions return `400` with `{"code": 400, "message": "..."}` and leave the round unchanged.
The round is kept in memory, so there is one round per server and it is lost on restart.
