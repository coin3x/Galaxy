package one.oktw.galaxy.types

import one.oktw.galaxy.Main.Companion.travelerManager
import one.oktw.galaxy.annotation.Document
import one.oktw.galaxy.types.item.Item
import one.oktw.galaxy.types.item.Upgrade
import java.util.*
import kotlin.collections.ArrayList

@Document
data class Traveler(
    val uuid: UUID,
    var position: Position,
    var armor: ArrayList<Upgrade> = ArrayList(),
    var item: ArrayList<Item> = ArrayList()
) {
    fun save() {
        travelerManager.saveTraveler(this)
    }
}
