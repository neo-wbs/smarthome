package monitoring;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;

public class CSVReader {
    public static void einlesenInQueue(File file, BlockingQueue<Messwert> queue, long msVerzoegerung) throws Exception {
        try(BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
        )) {
            String zeile = br.readLine(); //Header weglesen
            if(zeile == null) {
                return;
            }
            while((zeile = br.readLine()) != null) {
                Messwert messwert = extraktMesswert(zeile);
                if(messwert != null) {
                    queue.put(messwert); //produce
                    Thread.sleep(msVerzoegerung);
                }
            }
        }
    }

    public static Messwert extraktMesswert(String zeile) {
        String[] teile = zeile.split(",", 3);
        if(teile.length < 3) {
            return null;
        }
        try {
            String typ_raw = teile[0].trim();
            int posUS = typ_raw.lastIndexOf('_');
            String typ = typ_raw.substring(posUS + 1);
            double wert = Double.parseDouble(teile[1].trim());
            Instant zeitstempel = Instant.parse(teile[2].trim());
            return new Messwert(zeitstempel, wert, typ);
        } catch (Exception e) {
            return null; //Zeile überspringen
        }
    }
}
