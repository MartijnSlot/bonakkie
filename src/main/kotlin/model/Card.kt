package model

/** Ranks in sequence order: 7, 8, 9, 10, Jack, Queen, King, Ace. */
enum class Rank { SEVEN, EIGHT, NINE, TEN, JACK, QUEEN, KING, ACE }

enum class Suit { CLUBS, DIAMONDS, HEARTS, SPADES }

data class Card(val rank: Rank, val suit: Suit)
