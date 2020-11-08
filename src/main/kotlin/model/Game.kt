package model

class Game(private val players: MutableList<Player>) {

    fun start() {
        val deck = Deck()
        deal(players, deck)
    }

    private fun deal(players: MutableList<Player>, deck: Deck) {
        val handSize = if (players.size < 6) 6 else 5
        val chunkedDeck = deck.initial.chunked(handSize).toMutableList()
        players.forEach { player ->
            player.cardsInHand = Player.Hand(chunkedDeck[0].toMutableList())
            chunkedDeck.removeAt(0)
            println("PLAYER ${player.name} has hand: ${player.cardsInHand}")
        }
        deck.afterDeal = chunkedDeck.flatten().toMutableList()
    }
}