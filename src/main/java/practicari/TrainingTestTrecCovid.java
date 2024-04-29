package practicari;

//-evaljm 1-20 21-30 -cut 10 -metrica P -index índice_trec_covid/
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
        int cut = 0;
        String evalOption = null;
        String indexDir = null;
        int[] trainingQueries = new int[2];
        int[] testQueries = new int[2];
        String metric = null;

        for (int i = 0; i < args.length; i++) {
            System.out.println("Argumento actual: " + args[i]); 
            switch(args[i]) {
                case "-evaljm":
                    evalOption = "-evaljm";
                    evaljm = true;
                    evalOption = args[i];
                    String[] evalArgsJM = args[++i].split("-");
                    trainingQueries[0] = Integer.parseInt(evalArgsJM[0]);
                    trainingQueries[1] = Integer.parseInt(evalArgsJM[1]);
                    evalArgsJM = args[++i].split("-");
                    testQueries[0] = Integer.parseInt(evalArgsJM[0]);
                    testQueries[1] = Integer.parseInt(evalArgsJM[1]);
                    break;
                case "-evalbm25":
                    evalOption = "-evalbm25";
                    evalbm25 = true;
                    String[] evalArgsBM25 = args[++i].split("-");
                    trainingQueries[0] = Integer.parseInt(evalArgsBM25[0]);
                    trainingQueries[1] = Integer.parseInt(evalArgsBM25[1]);
                    testQueries[0] = Integer.parseInt(evalArgsBM25[2]);
                    testQueries[1] = Integer.parseInt(evalArgsBM25[3]);
                    break;
                case "-cut":
                    cut = tryParse(args[++i], "Parámetro \"cut\" no es un entero válido");  
                    break;
                case "-metrica":
                    metric = args[++i];
                    break;
                case "-index":
                    indexDir = args[++i];
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
    
    // Evalúa y optimiza un modelo de recuperación de información .
    private static void evaluateAndOptimizeModel(IndexSearcher searcher, Analyzer analyzer, int[] trainingQueries, int[] testQueries, int cut, String metric, double[] paramValues, String similarityType) throws IOException, ParseException {
        double bestParamValue = 0.0;
        double bestScore = Double.MIN_VALUE;
        double totalScore = 0.0;
        int totalQueries = 0;
    
        // Crear archivos CSV para entrenamiento y test
        File trainingCsvFile = new File("TREC-COVID." + similarityType.toLowerCase() + ".training." + trainingQueries[0] + "-" + trainingQueries[1] + ".test." + testQueries[0] + "-" + testQueries[1] + "." + metric + cut + ".training.csv");
        File testCsvFile = new File("TREC-COVID." + similarityType.toLowerCase() + ".training." + trainingQueries[0] + "-" + trainingQueries[1] + ".test." + testQueries[0] + "-" + testQueries[1] + "." + metric + cut + ".test.csv");
    
        // FileWriter para archivos CSV de entrenamiento y test
        FileWriter trainingWriter = new FileWriter(trainingCsvFile);
        FileWriter testWriter = new FileWriter(testCsvFile);
    
        // Escribir encabezados en archivos CSV de entrenamiento y test
        trainingWriter.write("Query,");
        testWriter.write("Query,");
        for (double param : paramValues) {
            trainingWriter.write(param + ",");
        }
        trainingWriter.write("\n");
        testWriter.write(metric + ",\n");
    
        // Procesar cada consulta de entrenamiento
        for (int i = trainingQueries[0]; i <= trainingQueries[1]; i++) {
            org.apache.lucene.search.Query query = buildQuery(i, analyzer);
            trainingWriter.write(i + ",");
            double[] scores = new double[paramValues.length];
            for (int j = 0; j < paramValues.length; j++) {
                if (similarityType.equals("JM")) {
                    searcher.setSimilarity(new LMJelinekMercerSimilarity((float) paramValues[j]));
                } else if (similarityType.equals("BM25")) {
                    BM25Similarity similarity = new BM25Similarity((float) paramValues[j], 0.75F);
                    searcher.setSimilarity(similarity);
                }
                double score = evaluateQuery(searcher, query, cut, metric);
                scores[j] = score;
                if (score > bestScore) {
                    bestScore = score;
                    bestParamValue = paramValues[j];
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
    
        // Procesar cada consulta de test
        for (int i = testQueries[0]; i <= testQueries[1]; i++) {
            org.apache.lucene.search.Query query = buildQuery(i, analyzer);
            double score = evaluateQuery(searcher, query, cut, metric);
            testWriter.write(i + ", " + score + "\n");
            totalScore += score;
            totalQueries++;
        }
    
        // Escribir promedio en archivo CSV de test
        double averageScore = totalScore / totalQueries;
        testWriter.write("Average, " + averageScore + "\n");
    
        // Cerrar FileWriter
        trainingWriter.close();
        testWriter.close();
    
        // Imprimir resultados en pantalla
        printResults(trainingCsvFile);
        printResults(testCsvFile);
    
        System.out.println("Best " + similarityType + " Value: " + bestParamValue);
    }
    
    //JM model
    private static void evaluateAndOptimizeJMModel(IndexSearcher searcher, Analyzer analyzer, int[] trainingQueries, int[] testQueries, int cut, String metric) throws IOException, ParseException {
        double[] lambdas = {0.001, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
        evaluateAndOptimizeModel(searcher, analyzer, trainingQueries, testQueries, cut, metric, lambdas, "JM");
    }
    
    //BM25 model
    private static void evaluateAndOptimizeBM25Model(IndexSearcher searcher, Analyzer analyzer, int[] trainingQueries, int[] testQueries, int cut, String metric) throws IOException, ParseException {
        double[] k1Values = {0.4, 0.6, 0.8, 1.0, 1.2, 1.4, 1.6, 1.8, 2.0};
        evaluateAndOptimizeModel(searcher, analyzer, trainingQueries, testQueries, cut, metric, k1Values, "BM25");    
    }

    private static org.apache.lucene.search.Query buildQuery(int queryId, Analyzer analyzer) throws ParseException {
        // Aquí construyes la cadena de consulta basada en el ID de la consulta real
        String queryString = "consulta_real_basada_en_el_id";
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
            // Determina si el documento en la posición i es relevante
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
    
    private static void printResults(File file) throws IOException {
        System.out.println("Results for " + file.getName() + ":");
        List<String> lines = Files.readAllLines(file.toPath());
        for (String line : lines) {
            System.out.println(line);
        }
    }

        // private static void createTrainingCSVFile(String model, int[] trainingQueries, int[] testQueries, String metric, int cut, double[] paramValues, double[] scores) {
    //     String fileName = "TREC-COVID." + model.toLowerCase() + ".training." + trainingQueries[0] + "-" + trainingQueries[1] + ".test." + testQueries[0] + "-" + testQueries[1] + "." + metric + cut + ".training.csv";
    //     try (FileWriter writer = new FileWriter(fileName)) {
    //         // Escribir encabezado
    //         writer.write("Query,");
    //         for (double param : paramValues) {
    //             writer.write(param + ",");
    //         }
    //         writer.write("\n");
    //         // Escribir resultados por consulta
    //         for (int i = 0; i < trainingQueries[1] - trainingQueries[0] + 1; i++) {
    //             writer.write((i + trainingQueries[0]) + ",");
    //             for (double score : scores) {
    //                 writer.write(score + ",");
    //             }
    //             writer.write("\n");
    //         }
    //         // Escribir fila de promedios
    //         writer.write("Average,");
    //         double total = 0;
    //         for (double score : scores) {
    //             total += score;
    //         }
    //         double average = total / scores.length;
    //         for (int i = 0; i < paramValues.length; i++) {
    //             writer.write(average + ",");
    //         }

    //         // Imprimir resultados por pantalla
    //         printResults(new File(fileName));

    //     } catch (IOException e) {
    //         e.printStackTrace();
    //     }
        
    // }
    
    // private static void createTestCSVFile(String model, int[] trainingQueries, int[] testQueries, String metric, double bestParamValue, double averageScore) {
    //     String fileName = "TREC-COVID." + model.toLowerCase() + ".training." + trainingQueries[0] + "-" + trainingQueries[1] + ".test." + testQueries[0] + "-" + testQueries[1] + "." + metric + ".test.csv";
    //     try (FileWriter writer = new FileWriter(fileName)) {
    //         // Escribir encabezado
    //         writer.write("Query,");
    //         writer.write(metric + ",\n");
    //         // Escribir resultados por consulta
    //         for (int i = testQueries[0]; i <= testQueries[1]; i++) {
    //             writer.write(i + ", " + averageScore + "\n");
    //         }
    //         // Imprimir resultados por pantalla
    //         printResults(new File(fileName));
            
    //         // Escribir fila de promedio
    //         writer.write("Average, " + averageScore + "\n");
    //     } catch (IOException e) {
    //         e.printStackTrace();
    //     }
        
    // }
}
