package practicari;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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
        
        boolean evaljm = false, evalbm25 = false;
        List<Integer> rango;
        int cut = 0;
        String evalOption = null;
        String indexDir = null;
        int[] trainingQueries = new int[2];
        int[] testQueries = new int[2];
        String metric = null;

        for (int i = 0; i < args.length; i++) {
            System.out.println("Argumento actual: " + args[i]); // Agregar esta línea para depurar
            switch(args[i]) {
                case "evaljm":
                    evalOption = "-evaljm";
                    evaljm = true;
                    rango = parseRange(args[i], args[i++]);
                    String[] evalArgsJM = args[++i].split("-");
                    if (evalArgsJM.length != 4) {
                        System.out.println("Argumentos incorrectos para -evaljm");
                        System.exit(0);
                    }
                    trainingQueries[0] = Integer.parseInt(evalArgsJM[0]);
                    trainingQueries[1] = Integer.parseInt(evalArgsJM[1]);
                    testQueries[0] = Integer.parseInt(evalArgsJM[2]);
                    testQueries[1] = Integer.parseInt(evalArgsJM[3]);
                    break;
                case "evalbm25":
                    evalOption = "-evalbm25";
                    evalbm25 = true;
                    rango = parseRange(args[i], args[i++]);
                    String[] evalArgsBM25 = args[++i].split("-");
                    if (evalArgsBM25.length != 4) {
                        System.out.println("Argumentos incorrectos para -evalbm25");
                        System.exit(0);
                    }
                    trainingQueries[0] = Integer.parseInt(evalArgsBM25[0]);
                    trainingQueries[1] = Integer.parseInt(evalArgsBM25[1]);
                    testQueries[0] = Integer.parseInt(evalArgsBM25[2]);
                    testQueries[1] = Integer.parseInt(evalArgsBM25[3]);
                    break;
                case "cut":
                    cut = tryParse(args[++i], "Parámetro \"cut\" no es un entero válido");  
                    break;
                case "-metrica":
                    metric = args[i++];
                    break;
                case "-index":
                    indexDir = args[i++];
                    break;
                default:
                    System.out.println("Argumento incorrecto: " + args[i]);
                    System.out.print(usage);
                    System.exit(0);
            }
        }

        validateParams(evaljm, evalbm25, cut, metric, indexDir);

        Path indexPath = Paths.get(indexDir);
        if (!Files.isDirectory(indexPath)) {
            System.out.println("El directorio para el índice \"" + indexPath + "\" no existe o no es un directorio.");
            System.exit(-1);
        }

        // Configurar el analizador
        Analyzer analyzer = new StandardAnalyzer();

                // Abrir el índice
        try (Directory dir = FSDirectory.open(Paths.get(indexDir));
            IndexReader indexReader = DirectoryReader.open(dir)) {

            // Crear el buscador
            IndexSearcher searcher = new IndexSearcher(indexReader);

            // Evaluar y optimizar el modelo
            if (evalOption.equals("-evaljm")) {
                evaluateAndOptimizeJMModel(searcher, analyzer, trainingQueries, testQueries, cut, metric);
            } else if (evalOption.equals("-evalbm25")) {
                evaluateAndOptimizeBM25Model(searcher, analyzer, trainingQueries, testQueries, cut, metric);
            }

        // try {
        //     indexDir = FSDirectory.open(indexPath);
        //     indexReader = DirectoryReader.open(indexDir);
        //     StoredFields storedFields = indexReader.storedFields();
        //     IndexSearcher searcher = new IndexSearcher(indexReader);
        //     QueryParser parser = new QueryParser("", new StandardAnalyzer());


            // if(evaljm) {
            //     float lambda = 0.001F;
            //     while (lambda <= 1.0F) {
            //         LMJelinekMercerSimilarity similarity = new LMJelinekMercerSimilarity(0.001F);

            //         // lanzar queries

            //         if (lambda == 0.001F){
            //             lambda = 0.1F;
            //         } else {
            //             lambda = lambda + 0.1F;
            //         }
            //     }

            // }
            // else {
            //     float k1 = 0.4F;
            //     while (k1 <= 2.0) {
            //         BM25Similarity similarity = new BM25Similarity(k1, 0.75F);    // valor de b por defecto

            //         k1 = k1 + 0.2F;
            //     }

            // }

        } catch (IOException | ParseException e) {
            throw new RuntimeException(e);
        }

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
    
    private static void evaluateAndOptimizeJMModel(IndexSearcher searcher, Analyzer analyzer,int[] trainingQueries, int[] testQueries, int cut, String metric) throws IOException, ParseException {
        double[] lambdas = {0.001, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
        double bestLambda = 0.0;
        double bestScore = Double.MIN_VALUE;

        File trainingCsvFile = new File("TREC-COVID.jm.training." + trainingQueries[0] + "-" + trainingQueries[1] + ".test." + testQueries[0] + "-" + testQueries[1] + "." + metric + cut + ".training.csv");
        FileWriter trainingWriter = new FileWriter(trainingCsvFile);

        trainingWriter.write("Query,");
        for (double lambda : lambdas) {
            trainingWriter.write(lambda + ",");
        }
        trainingWriter.write("\n");

        for (int i = trainingQueries[0]; i <= trainingQueries[1]; i++) {
            org.apache.lucene.search.Query query = buildQuery(i, analyzer);
            trainingWriter.write(i + ",");
            double[] scores = new double[lambdas.length];
            for (int j = 0; j < lambdas.length; j++) {
                searcher.setSimilarity(new LMJelinekMercerSimilarity((float) lambdas[j]));
                double score = evaluateQuery(searcher, query, cut, metric);
                scores[j] = score;
                if (score > bestScore) {
                    bestScore = score;
                    bestLambda = lambdas[j];
                }
            }
            Arrays.stream(scores).forEach(score -> {
                try {
                    trainingWriter.write(score + ",");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            trainingWriter.write("\n");
        }
        System.out.println("Best Lambda: " + bestLambda);

        // Use the best lambda on test queries
        evaluateTestQueries(searcher, analyzer, trainingQueries, bestLambda, testQueries, cut, metric);
        trainingWriter.close();
    }

    private static org.apache.lucene.search.Query buildQuery(int queryId, Analyzer analyzer) throws ParseException {
        // Construir una cadena de consulta basada en el ID de la consulta
        String queryString = "your query string here";
        
        // Utilizar el QueryParser para analizar la cadena de consulta y obtener la consulta
        QueryParser parser = new QueryParser("fieldName", analyzer);
        return parser.parse(queryString);
    }
    

    private static double evaluateQuery(IndexSearcher searcher, org.apache.lucene.search.Query query, int cut, String metric) throws IOException {
        // Realizar la búsqueda utilizando la consulta dada
        TopDocs topDocs = searcher.search(query, cut);
        
        // Obtener los documentos relevantes para la consulta (si están disponibles)
        ScoreDoc[] hits = topDocs.scoreDocs;
        // Calcular la métrica de evaluación (por ejemplo, precisión P@k)
        double precision = calculatePrecision(hits, cut);
        return precision;
    }


    private static void evaluateTestQueries(IndexSearcher searcher, Analyzer analyzer, int[] trainingQueries, double bestLambda, int[] testQueries, int cut, String metric) throws IOException, ParseException {
        File testCsvFile = new File("TREC-COVID.jm.training." + trainingQueries[0] + "-" + trainingQueries[1] + ".test." + testQueries[0] + "-" + testQueries[1] + "." + metric + cut + ".test.csv");
        FileWriter testWriter = new FileWriter(testCsvFile);
    
        // Write header for test CSV file
        testWriter.write("Query, " + metric + "\n");
    
        double totalScore = 0.0;
        int totalQueries = 0;
    
        for (int i = testQueries[0]; i <= testQueries[1]; i++) {
            org.apache.lucene.search.Query query = buildQuery(i, analyzer);
            double score = evaluateQuery(searcher, query, cut, metric);
            testWriter.write(i + ", " + score + "\n");
            totalScore += score;
            totalQueries++;
        }
    
        double averageScore = totalScore / totalQueries;
        testWriter.write("Average, " + averageScore + "\n");
    
        // Print the results to the console
        System.out.println("Results for test queries:");
        // Leer el archivo CSV y mostrar los resultados en la consola
    
        testWriter.close();
    }

    private static void evaluateAndOptimizeBM25Model(IndexSearcher searcher, Analyzer analyzer, int[] trainingQueries, int[] testQueries, int cut, String metric) throws IOException, ParseException {
        double[] k1Values = {0.4, 0.6, 0.8, 1.0, 1.2, 1.4, 1.6, 1.8, 2.0};
        double bestK1 = 0.0;
        double bestScore = Double.MIN_VALUE;

        File trainingCsvFile = new File("TREC-COVID.bm25.training." + trainingQueries[0] + "-" + trainingQueries[1] + ".test." + testQueries[0] + "-" + testQueries[1] + "." + metric + cut + ".training.csv");
        FileWriter trainingWriter = new FileWriter(trainingCsvFile);

        trainingWriter.write("Query,");
        for (double k1 : k1Values) {
            trainingWriter.write(k1 + ",");
        }
        trainingWriter.write("\n");

        for (int i = trainingQueries[0]; i <= trainingQueries[1]; i++) {
            org.apache.lucene.search.Query query = buildQuery(i, analyzer);
            trainingWriter.write(i + ",");
            double[] scores = new double[k1Values.length];
            for (int j = 0; j < k1Values.length; j++) {
                BM25Similarity similarity = new BM25Similarity((float) k1Values[j], 0.75F);
                searcher.setSimilarity(similarity);
                double score = evaluateQuery(searcher, query, cut, metric);
                scores[j] = score;
                if (score > bestScore) {
                    bestScore = score;
                    bestK1 = k1Values[j];
                }
            }
            Arrays.stream(scores).forEach(score -> {
                try {
                    trainingWriter.write(score + ",");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            trainingWriter.write("\n");
        }
        System.out.println("Best k1 Value: " + bestK1);

        // Use the best k1 value on test queries
        evaluateTestQueries(searcher, analyzer, trainingQueries, bestK1, testQueries, cut, metric);
        trainingWriter.close();
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

    private static double calculatePrecision(ScoreDoc[] hits, int cut) {
        int relevantCount = 0;
        for (int i = 0; i < Math.min(hits.length, cut); i++) {
            // Aquí determina si el documento en la posición i es relevante
            // Puedes ajustar esta lógica según tu caso de uso específico
            if (isRelevantDocument(hits[i], null)) {
                relevantCount++;
            }
        }
        return (double) relevantCount / Math.min(hits.length, cut);
    }

    private static boolean isRelevantDocument(ScoreDoc hit, List<Integer> relevantDocumentIds) {
        // Obtiene el ID del documento recuperado
        int docId = hit.doc;
        
        // Comprueba si el ID del documento está en la lista de IDs de documentos relevantes
        return relevantDocumentIds.contains(docId);
    }
    
}
