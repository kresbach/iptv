package app.iptv.tv.player

/** Berechnet die Bildfläche, die das Video im Originalformat ausfüllt (schwarze Ränder statt Strecken). */
object VideoFit {
    /**
     * @param sarNum/sarDen Pixel-Seitenverhältnis (anamorphe Videos, z. B. 720×576 mit 64:45 = 16:9)
     * @return Breite und Höhe der Bildfläche in Pixeln, zentriert im Container
     */
    fun fit(containerW: Int, containerH: Int, videoW: Int, videoH: Int, sarNum: Int = 1, sarDen: Int = 1): Pair<Int, Int> {
        if (containerW <= 0 || containerH <= 0 || videoW <= 0 || videoH <= 0) return containerW to containerH
        val sar = if (sarNum > 0 && sarDen > 0) sarNum.toDouble() / sarDen else 1.0
        val aspect = videoW * sar / videoH
        val containerAspect = containerW.toDouble() / containerH
        return if (containerAspect > aspect) {
            // Fernseher breiter als Video (z. B. 4:3) → Ränder links/rechts
            (containerH * aspect).toInt() to containerH
        } else {
            // Video breiter (z. B. 2,39:1 Kino) → Ränder oben/unten
            containerW to (containerW / aspect).toInt()
        }
    }
}
