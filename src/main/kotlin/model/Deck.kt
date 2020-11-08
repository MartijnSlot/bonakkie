package model

class Deck {
    var initial: MutableList<Card> = mutableListOf()
    var afterDeal: MutableList<Card> = mutableListOf()

    init {
        Type.values().forEach { type ->
            Suit.values().forEach { suit ->
                initial.add(Card(type, suit, false))
                afterDeal.add(Card(type, suit, false))
            }
        }
        initial.shuffle()
    }

    fun deal(players: MutableList<Player>): MutableList<Player> {
        val handSize = if (players.size < 6) 6 else 5
        val chunkedDeck = initial.chunked(handSize).toMutableList()

        players.forEach { player ->
            player.cardsInHand = Player.Hand(chunkedDeck.first().toMutableList())
            chunkedDeck.removeFirst()
            println("PLAYER ${player.name} has hand: ${player.cardsInHand}")
        }
        afterDeal = chunkedDeck.flatten().toMutableList()
        return players
    }

    fun getStok(): List<Card> {
        return this.afterDeal.subList(0,1)
    }
}

data class Card(val type: Type, val suit: Suit, val revealed: Boolean)

enum class Type { SEVEN, EIGHT, NINE, TEN, JACK, QUEEN, KING, ACE }

enum class Suit { CLUBS, DIAMONDS, HEARTS, SPADES }
