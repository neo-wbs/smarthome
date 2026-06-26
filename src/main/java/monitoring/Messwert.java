package monitoring;

import java.time.Instant;

//record: generiert Konstruktor, getter/setter, equals, hashcode selbst
//nicht: getWert, sondern wert
public record Messwert (Instant zeitstempel, double wert, String typ) {}
