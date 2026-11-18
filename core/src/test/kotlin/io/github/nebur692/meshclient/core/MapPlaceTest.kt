package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A device's place on the map, as the website's map_parseNodeLoc and the Windows app's MapPlaceTests (expected values
 * computed with the website's JavaScript). / El sitio de un equipo en el mapa, como la web y la app de Windows.
 */
class MapPlaceTest {
    private val id = "node//WUMttOt6AvFnB1OL0TKLkPuTqEGIHkic56q3SYpQyMJhqXitn323@s@Q4C\$g\$dZa"

    private fun of(json: String) = Device.from(Json.parseToJsonElement(json) as JsonObject)

    @Test fun `the place set by hand beats GPS, Wi-Fi and the address`() {
        assertEquals(MapPlace(40.4168, -3.7038, MapPlaceKind.User),
            of("""{"_id":"$id","iploc":"1,1,5","wifiloc":"2,2,5","gpsloc":"3,3,5","userloc":"40.4168,-3.7038,5"}""").mapPlace)
        assertEquals(MapPlaceKind.Gps, of("""{"_id":"$id","iploc":"1,1,5","wifiloc":"2,2,5","gpsloc":"3,3,5"}""").mapPlace!!.kind)
        assertNull(of("""{"_id":"$id"}""").mapPlace)
        assertNull(of("""{"_id":"$id","userloc":"95,0,5"}""").mapPlace)
    }

    @Test fun `a place from the address moves as the website moves it`() {
        val p = of("""{"_id":"$id","iploc":"40.4168,-3.7038,5"}""").mapPlace!!
        assertEquals(MapPlaceKind.Ip, p.kind)
        assertEquals(40.420819793440, p.latitude, 1e-9)
        assertEquals(-3.698579912540, p.longitude, 1e-9)
    }
}
