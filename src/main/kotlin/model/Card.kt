package model

sealed class Card {
    data class Clubs(val type: Type) : Card()
    data class Diamonds(val type: Type) : Card()
    data class Hearts(val type: Type) : Card()
    data class Spades(val type: Type) : Card()
}

enum class Type {
    EIGHT, NINE, TEN, JACK, QUEEN, KING, ACE
}