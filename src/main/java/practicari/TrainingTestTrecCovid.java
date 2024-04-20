package practicari;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;

public class TrainingTestTrecCovid {
    // TODO: tener en cuenta queries con AND y OR
    private static final String usage = "Uso:\n" +
            "-evaljm <int1-int2> <int3-int4>\n" +
            "-evalbm25 <int1-int2> <int3-int4> (las opciones -evaljm -evalbm25 son mutuamente excluyentes)\n" +
            "-cut <n>: n indica el corte en el ranking para el cómputo de la métrica.\n" +
            "-metrica P | R | MRR | MAP: indica la métrica computada y optimizada en el corte n.\n" +
            "-index <ruta>: ruta de la carpeta que contiene el índice.\n";
    public static void main(String[] args) {
        int int1, int2, int3, int4;
        List<Integer> rango;
        boolean evaljm = false, evalbm25 = false;
        int cut = -1;
        String metrica = null;
        String index = null;

        for (int i = 0; i < args.length; i++) {
            switch(args[i]) {
                case "-evaljm":
                    evaljm = true;
                    rango = parseRange(args[i], args[i++]);
                    break;
                case "evalbm25":
                    evalbm25 = true;
                    rango = parseRange(args[i], args[i++]);
                    break;
                case "cut":
                    cut = tryParse(args[i++], "Parámetro \"cut\" no es un entero válido");
                    break;
                case "-metrica":
                    metrica = args[i++];
                    break;
                case "-index":
                    index = args[i++];
                    break;
                default:
                    System.out.println("Argumento incorrecto: " + args[i]);
                    System.out.print(usage);
                    System.exit(0);
            }
        }

        validateParams(evaljm, evalbm25, cut, metrica, index);

        FSDirectory indexDir;
        DirectoryReader indexReader;

        Path indexPath = Paths.get(index);
        if (!Files.isDirectory(indexPath)) {
            System.out.println("El directorio para el índice \"" + indexPath + "\" no existe o no es un directorio.");
            System.exit(-1);
        }

        try {
            indexDir = FSDirectory.open(indexPath);
            indexReader = DirectoryReader.open(indexDir);
            StoredFields storedFields = indexReader.storedFields();
            IndexSearcher searcher = new IndexSearcher(indexReader);
            QueryParser parser = new QueryParser("", new StandardAnalyzer());


            /*
            Sobre el índice indicado en pathname se lanzan las queries con el modelo LM Jelinek-Mercer
            (BM25) de int1 a int2 y se evalúa la métrica en el corte n para esas queries. Se repite el proceso
            moviendo lambda con los valores 0.001, 0.1 0.2, … , 1.0, (k1 0.4, 0.6, 0.8, …,2.0). Con el mejor
            valor de lambda (k1) en training se lanzan las queries de test de int3 a int4 y se informa del valor de
            la métrica con corte n obtenido para las queries de test y su promedio.
             */

            if(evaljm) {
                float lambda = 0.001F;
                while (lambda <= 1.0F) {
                    LMJelinekMercerSimilarity similarity = new LMJelinekMercerSimilarity(0.001F);

                    // lanzar queries

                    if (lambda == 0.001F){
                        lambda = 0.1F;
                    } else {
                        lambda = lambda + 0.1F;
                    }
                }

            }
            else {
                float k1 = 0.4F;
                while (k1 <= 2.0) {
                    BM25Similarity similarity = new BM25Similarity(k1, 0.75F);    // valor de b por defecto

                    k1 = k1 + 0.2F;
                }

            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        /*
        Debe construirse un archivo .csv con los resultados del proceso de training con una fila por query,
        una columna por valor de lambda (k1), una fila cabecera informando de los valores de lambda (k1),
        la primera filaprimera columna para informar de la métrica y el corte, y una última fila con los promedios.

        Ej: TREC-COVID.jm.training.1-20.test.21-30.map10.training.csv: muestra los resultados de
            training sobre las queries 1 al 20 con los valores de lambda JM optimizados para MAP@10
         */

        /*
        Debe construirse otro archivo con los resultados del proceso de test: una fila por query, una
        columna para indicar el valor de la métrica, una fila cabecera informando del nombre de la métrica,
        la primera fila primera columna para indicar el valor del lambda en test (lamda óptimo en training),
        y una última fila con los promedios. El nombre de este archivo a modo de ejemplo será:

        TREC-COVID.jm.training.1-20.test.21-30.map10.test.csv: muestra los resultados de test
            sobre las queries 21-30 para el modelo JM cuyo lambda se optimizó en las queries 1 al 20,
            tanto la optimización y el test con MAP@10.
         */

        /*
        -evaljm y -evalbm25, después de producir los archivos csv también volcarán su contenido por pantalla
         */

    }

    private static void validateParams(boolean evaljm, boolean evalbm25, int cut, String metrica, String indexPath) {
        if (evaljm && evalbm25) {
            System.out.println("Sólo se acepta evaljm o evalbm25, no ambos.\n");
            System.exit(0);
        } else if (!evaljm && !evalbm25) {
            System.out.println("El valor evaljm o el evalbm25 son obligatorios.\n");
            System.exit(0);
        } else if (cut < 1) {
            System.out.println("Argumento \"cut\" inválido.\n");
            System.exit(0);
        } else if (metrica == null) {
            System.out.println("El argumento \"metrica\" es obligatorio.\n");
            System.exit(0);
        } else if (indexPath == null) {
            System.out.println("El argumento \"index\" es obligatorio.\n");
            System.exit(0);
        } else if (!metrica.equals("P") && !metrica.equals("R") && !metrica.equals("MRR") && !metrica.equals("MAP")) {
            System.out.println("El argumento \"metrica\" no tiene un valor válido.\n");
            System.exit(0);
        }
    }

    private static int tryParse(String text, String errorMessage) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            System.err.println(errorMessage);
            System.exit(-1);
        }
        return 0;
    }

    public static List<Integer> parseRange(String range1, String range2) {
        List<Integer> l = new ArrayList<>();
        String[] a = range1.split("-");
        String[] b = range2.split("-");
        try {
            l.add(Integer.parseInt(a[0]));
            l.add(Integer.parseInt(a[1]));
            l.add(Integer.parseInt(b[0]));
            l.add(Integer.parseInt(b[1]));
            return l;
        } catch (NumberFormatException e) {
            System.err.println(e.getMessage());
            System.exit(-1);
        }
        return l;
    }
}
