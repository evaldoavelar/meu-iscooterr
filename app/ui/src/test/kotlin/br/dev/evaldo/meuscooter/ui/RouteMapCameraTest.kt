package br.dev.evaldo.meuscooter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S49: contratos do pedido e câmera 2× dos PNGs aprovados, sem Compose nem rede. */
class RouteMapCameraTest {
    @Test fun `mais dobra a escala e centralizar volta ao enquadramento inicial`() {
        val initial = RouteMapCamera()
        assertFalse(initial.canRecenter)
        val zoomed = initial.transform(2f, 0f, 0f, 200f, 100f)
        assertEquals(2f, zoomed.scale, 0f)
        assertTrue(zoomed.canRecenter)
        assertEquals(initial, zoomed.recenter())
    }
    @Test fun `zoom respeita limites e rejeita entradas invalidas`() {
        val initial = RouteMapCamera()
        assertEquals(1f, initial.transform(0.5f, 0f, 0f, 200f, 100f).scale, 0f)
        assertEquals(16f, initial.transform(100f, 0f, 0f, 200f, 100f).scale, 0f)
        assertEquals(4f, initial.transform(8f, 0f, 0f, 200f, 100f, maxScale = 4f).scale, 0f)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f))
            assertEquals(initial, initial.transform(bad, 0f, 0f, 200f, 100f))
        assertEquals(initial, initial.transform(2f, Float.NaN, 0f, 200f, 100f))
        assertEquals(initial, initial.transform(2f, 0f, Float.POSITIVE_INFINITY, 200f, 100f))
        assertEquals(initial, initial.transform(2f, 0f, 0f, 0f, 100f))
        assertEquals(initial, initial.transform(2f, 0f, 0f, 200f, 0f))
        assertEquals(initial, initial.transform(2f, 0f, 0f, 200f, 100f, focusX = Float.NaN))
        assertEquals(initial, initial.transform(2f, 0f, 0f, 200f, 100f, focusY = Float.NaN))
    }

    @Test fun `arrasto funciona no enquadramento inicial e tem limite nos dois sentidos`() {
        val panned = RouteMapCamera().transform(1f, 500f, -500f, 200f, 100f)
        assertEquals(RouteMapCamera(1f, 100f, -50f), panned)
        assertTrue(panned.canRecenter)
        assertEquals(RouteMapCamera(2f, -200f, 100f), panned.transform(2f, -1000f, 1000f, 200f, 100f))
        assertEquals(RouteMapCamera(), panned.recenter())
    }

    @Test fun `zoom na ancora preserva o ponto tocado e menos desfaz mais`() {
        // Ponto (150,25) a (50,-25) do centro: ao dobrar, offset (-50,25) o mantém parado.
        val zoomed = RouteMapCamera().transform(2f, 0f, 0f, 200f, 100f, 150f, 25f)
        assertEquals(RouteMapCamera(2f, -50f, 25f), zoomed)
        assertEquals(RouteMapCamera(), zoomed.transform(0.5f, 0f, 0f, 200f, 100f, 150f, 25f))
    }
    @Test fun `barra de escala usa distancia geografica e rotulos em portugues`() {
        assertEquals(100.0, mapScaleDistance(120.0), 0.0)
        assertEquals(50.0, mapScaleDistance(60.0), 0.0)
        assertEquals(20.0, mapScaleDistance(49.0), 0.0)
        assertEquals(1.0, mapScaleDistance(Double.NaN), 0.0)
        assertEquals(1.0, mapScaleDistance(0.0), 0.0)
        assertEquals("100 m", formatMapScale(100.0))
        assertEquals("50 m", formatMapScale(50.0))
        assertEquals("0,5 m", formatMapScale(0.5))
        assertEquals("1 km", formatMapScale(1000.0))
        assertEquals("2,5 km", formatMapScale(2500.0))
    }
}
