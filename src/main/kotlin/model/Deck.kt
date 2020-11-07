package model

class Deck {
    var cards = mutableListOf<Card>()

    init {
        for (type in Type.values()) {
            cards.add(Card.Diamonds(type))
            cards.add(Card.Hearts(type))
            cards.add(Card.Clubs(type))
            cards.add(Card.Spades(type))
        }
        cards.shuffle()
    }
}