package model

class Player(val name: String) {
    val points: Int = 6
    var cardsInHand: Hand? = null

    data class Hand(val cards: MutableList<Card>)
}
