package model

class Game(private val players: MutableList<Player>) {

    fun start() {
        val deck = Deck()
        deck.deal(players, deck)
    }
}
