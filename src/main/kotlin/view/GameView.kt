package view

import com.fasterxml.jackson.annotation.JsonAutoDetect
import model.Card
import model.Player

@JsonAutoDetect
class GameView(
    val players: List<Player>,
    val stok: List<Card>
)