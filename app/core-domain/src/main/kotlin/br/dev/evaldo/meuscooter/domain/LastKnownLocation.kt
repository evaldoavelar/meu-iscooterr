package br.dev.evaldo.meuscooter.domain

/**
 * Última posição conhecida do aparelho (ADR 0012, adendo S45): só em memória, usada apenas para centrar tiles desfocados.
 * `toString` omite as coordenadas para não vazarem em log/exceção.
 */
class LastKnownLocation(val point: GeoPoint) {
    override fun toString() = "LastKnownLocation"
}

/** Fonte da última posição conhecida. Nunca pede permissão nova; sem permissão/posição devolve `null`. Não bloqueia a thread chamadora. */
fun interface LastKnownLocationSource {
    suspend fun lastKnown(): LastKnownLocation?

    companion object {
        val None = LastKnownLocationSource { null }
    }
}

/** Tiles do mapa desfocado: zoom único e quadro reduzido pela metade ([SCALE]) para caber em 1–4 tiles por cartão (política de uso do OSM). */
object LastKnownMap {
    const val ZOOM = 14
    const val SCALE = 2f

    /** Posições em px reais; [density] converte dp em px. */
    fun placements(center: GeoPoint, widthPx: Float, heightPx: Float, density: Float): List<TilePlacement> {
        val k = SCALE * density
        return MapViewport.centered(center, ZOOM, widthPx / k, heightPx / k).tiles()
            .map { TilePlacement(it.tile, it.left * k, it.top * k, it.size * k) }
    }
}
