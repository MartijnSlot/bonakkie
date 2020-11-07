package model

class Game(val players: MutableList<Player>) {

    fun start() {
        val deck = Deck()
        deal(players, deck)
    }

    private fun deal(players: MutableList<Player>, deck: Deck) {
        players.forEach { player ->
            for (i: Int in 0 until deck.cards.size) {
                player.cardsInHand = Player.Hand(deck.cards.subList(i, i+5))
                deck.cards.removeAt(i)
                deck.cards.removeAt(i+1)
                deck.cards.removeAt(i+2)
                deck.cards.removeAt(i+3)
                deck.cards.removeAt(i+4)
                deck.cards.removeAt(i+5)
            }
            println(player.cardsInHand)
        }
    }
}