package model

class Deck {
    var initial: MutableList<Card> = mutableListOf()
    var afterDeal: MutableList<Card> = mutableListOf()

    init {
        for (type in Type.values()) {
            initial.add(Card.Diamonds(type))
            initial.add(Card.Hearts(type))
            initial.add(Card.Clubs(type))
            initial.add(Card.Spades(type))
        }
        initial.shuffle()
    }
}