# bonakkie

A Bonak **bidding-only** backend. It runs the bidding auction for one deal over an HTTP API and
stops at a final contract (or a redeal). It does not deal cards, play tricks, choose trumps, or
score hands. There is no frontend yet.

## Features

- Configure the players, their clockwise seating order, and the dealer.
- Run the auction: `ROND`, `PASS`, `BID_POINTS(amount)`, `BID_PRAAT`, `BID_BONAK`, and
  `BID_BONAK_WITH_ROEM(amount)`. The server validates every action.
- Read the current turn, legal actions, bidding history, passed players, and Praat participants.
- Get one of these outcomes: a finalized contract, a required redeal (everyone said Rond on the
  opening circuit), or an unresolved state (everyone passed without a bid; the rules don't cover this).
- Look up points-contract requirements: trick points still needed after roem, plus at least one trick.
- Get the roem combination table and calculate roem for a set of cards and a trump suit.

## Rules implemented

- Bidding starts left of the dealer and goes clockwise, skipping players who passed.
- **Pass** is permanent for the auction.
- **Rond** skips a turn and keeps the right to bid later. It is only allowed while every earlier
  action in the auction was Rond.
- **Points** bids run from 50 to 125 in steps of 5. Each bid must be higher than the last, and a
  bid over Praat must be at least 100. The bid includes roem. A points contract needs
  `max(bid - roem, 0)` trick points and at least one trick.
- **Praat** (take no tricks) can be bid before any points bid, or over a points bid of 75 or less.
  Other players may join it. Bonak or a points bid of 100 or more beats it.
- **Bonak** (take every trick with trumps) beats any points bid and Praat. After a Bonak, only
  Bonak with more roem can outbid it. That roem must be one of 20, 40, 50, 60, 70, 80, 90 or 100.
- **Roem**: sequences of 3, 4 and 5+ cards score 20, 50 and 100. Stuk (trump King and Queen) adds
  20. Four Aces, Kings, Queens, Jacks or Tens score 100 each. Combinations add up and the total is
  capped at 100.

The domain code lives in `src/main/kotlin/auction` (auction state machine) and
`src/main/kotlin/roem` (roem calculator).

## Running

Kotlin, Dropwizard, JDK 25. The server listens on http://localhost:6969:

```
./gradlew run
./gradlew test
```

## API

| Method | Path | Body / query |
|---|---|---|
| `POST` | `/api/auction` | `{"players": [...], "dealer": "..."}` starts a fresh auction |
| `GET` | `/api/auction` | current auction, including `legalActions` |
| `POST` | `/api/auction/actions` | `{"player": "...", "type": "BID_POINTS", "amount": 80}` |
| `GET` | `/api/auction/contract/requirements` | `?roem=40` (points contracts only) |
| `GET` | `/api/roem/combinations` | roem table |
| `POST` | `/api/roem/calculate` | `{"trump": "HEARTS", "cards": [{"rank": "KING", "suit": "HEARTS"}]}` |

Rejected actions return `400` with `{"code": 400, "message": "..."}` and leave the auction unchanged.
The auction is kept in memory, so there is one auction per server and it is lost on restart.
