package monitoring;

import java.util.ArrayList;
import java.util.List;

// Eine LaufzeitStatistik-Instanz ist pro Typ (z.B. "temperature"), nicht pro Messwert.
// Über die Laufzeit kommen viele Werte für denselben Typ rein, und jeder davon kann (muss aber nicht)
// die Schwelle überschreiten und einen Alarm erzeugen. Deshalb ist alarme eine Liste: bei z.B. 50 Temperaturwerten
// können 0 bis 50 Alarme entstehen — nicht "ein Alarm oder keiner pro Statistik", sondern beliebig viele Alarme pro Statistik.

// Also pro Sensor-Typ (z.B. "temperature") existiert eine LaufzeitStatistik-Instanz, jede liefert ihr eigenes Snapshot.
// Sammelt Werte EINER NACH DEM ANDEREN ein - passend zum Producer/Consumer-Muster,
// bei dem die Messwerte nicht alle auf einmal, sondern nach und nach eintrudeln.
public class LaufzeitStatistik {
    // Snapshot — der eingefrorene Zustand.
    // Während LaufzeitStatistik sich laufend verändert (neue Werte kommen über add() rein),
    // ist ein Snapshot ein unveränderliches Foto von genau einem Moment - z.B. dem Moment,
    // in dem der Nutzer auf "Export" klickt. Record statt eigener Klasse, weil hier nur
    // Daten transportiert werden, keine Logik mehr nötig ist.
    public record Snapshot(String typ, double min, double max, double avg,
                            int anzahl, List<Alarm> alarme) {
    }

    public final String typ;
    private double min = Double.MAX_VALUE;
    private double max = -Double.MAX_VALUE;
    private double sum = 0;
    private int count = 0;
    private final List<Alarm> alarme = new ArrayList<>();

    public LaufzeitStatistik(String typ) {
        this.typ = typ;
    }

    // Wird vom Consumer-Thread für jeden neuen Messwert aufgerufen.
    // Schwellenwert wird hier (nicht im Konstruktor) übergeben, damit der Nutzer
    // den Schwellenwert während des Laufs in der GUI ändern kann.
    public void add(Messwert messwert, double schwellenwert) {
        double wert = messwert.wert();
        if (wert < min) {
            min = wert;
        }
        if (wert > max) {
            max = wert;
        }
        sum += wert;
        count++;
        if (wert > schwellenwert) {
            alarme.add(new Alarm(messwert.zeitstempel(), wert, schwellenwert, typ));
        }
    }

    public int count() {
        return count;
    }

    public double min() {
        return count == 0 ? 0 : min;
    }

    public double max() {
        return count == 0 ? 0 : max;
    }

    public double avg() {
        return count == 0 ? 0 : sum / count;
    }

    public List<Alarm> alarme() {
        return alarme;
    }

    // Wandelt die laufenden Werte in einen unveränderlichen Snapshot um,
    // z. B. für den Export als JSON/XML. Ohne diesen Schritt würde der Export
    // entweder live weiterlaufende Werte sehen (inkonsistent, siehe Erklärung unten)
    // oder direkt auf die private Alarm-Liste zugreifen müssen (kein Kapselungsschutz).
    public Snapshot snapshot() {
        return new Snapshot(typ, min(), max(), avg(), count, new ArrayList<>(alarme)); // Das ist eine Kopie der Alarm-Liste.
    }
}
/*
Warum man einen "eingefrorenen Zustand" braucht:

Das Problem ist nicht die Klassenanzahl, sondern der Zugriff durch zwei verschiedene Threads gleichzeitig:

Der Consumer-Thread ruft pausenlos add() auf LaufzeitStatistik auf, solange CSV-Zeilen eintrudeln (min/max/sum/count/alarme ändern sich laufend).
Wenn der Nutzer währenddessen auf "Export" klickt, läuft das im EDT (GUI-Thread) – also parallel zum Consumer.

Wenn exportJson() direkt auf die LaufzeitStatistik-Felder zugreifen würde, könnte es passieren, dass z. B. min schon
den neuen Wert hat, aber alarme noch nicht den passenden neuen Alarm enthält (oder umgekehrt) – während der Export
gerade mitten im Schreiben ist. Das Ergebnis wäre eine Datei mit inkonsistenten Werten, die nie wirklich so existiert haben.

snapshot() löst das, indem es an einem einzigen Moment alle Werte abliest und in ein unveränderliches Objekt
kopiert (new ArrayList<>(alarme) ist hierbei der entscheidende Punkt – eine echte Kopie, keine Referenz auf die Live-Liste).
Ab diesem Moment kann der Consumer-Thread weiterlaufen und die Originalwerte ändern, der Snapshot bleibt aber garantiert stabil,
während Exporter ihn in Ruhe in JSON/XML schreibt.
 */