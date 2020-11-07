package model

class Player(val name: String) {
    val id: Int = 0
    val points: Int = 6
    var cardsInHand: Hand? = null

    data class Hand(val cards: MutableList<Card>)
}
