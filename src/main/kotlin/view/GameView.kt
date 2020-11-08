package view

import com.fasterxml.jackson.annotation.JsonAutoDetect
import model.Card
import model.Player

/**
 * Created by martijn.slot on 14/09/17.
 */
@JsonAutoDetect
class GameView(
    val players: List<Player>,
    val stok: List<Card>
)