package monitoring;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CsvReaderTest {

    @Test
    void gueltigeZeileWirdKorrektGeparst() {
        String line = "sensor.tempsensor4_temperature,22.15,2026-01-01T00:00:00.000Z";
        Messwert m = CSVReader.extraktMesswert(line);

        assertNotNull(m);
        assertEquals("temperature", m.typ());
        assertEquals(22.15, m.wert(), 0.0001);
        assertEquals(Instant.parse("2026-01-01T00:00:00.000Z"), m.zeitstempel());
    }

    @Test
    void typWirdAusEntityIdNachLetztemUnterstrichExtrahiert() {
        Messwert m = CSVReader.extraktMesswert("sensor.foo_bar_humidity,40.0,2026-01-01T00:00:00.000Z");
        assertEquals("humidity", m.typ()); // nur Teil NACH dem letzten '_'
    }

    @Test
    void zeileMitZuWenigSpaltenGibtNullZurueck() {
        assertNull(CSVReader.extraktMesswert("nur,zweiSpalten"));
    }

    @Test
    void nichtParsbarerZahlenwertGibtNullZurueck() {
        assertNull(CSVReader.extraktMesswert("sensor.x_temperature,NICHT_EINE_ZAHL,2026-01-01T00:00:00.000Z"));
    }

    @Test
    void nichtParsbaresDatumGibtNullZurueck() {
        assertNull(CSVReader.extraktMesswert("sensor.x_temperature,22.0,KEIN_DATUM"));
    }

    @Test
    void leereZeileGibtNullZurueck() {
        assertNull(CSVReader.extraktMesswert(""));
    }
}
