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

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.apache.lucene.document.Document;

import java.nio.file.Files;
import org.apache.lucene.search.Query;

public class TrainingTestTrecCovid {
    private static final String queryFilePath = "trec-covid" + File.separator + "queries.jsonl";
    private static final String testFilePath = "trec-covid" + File.separator + "qrels" + File.separator + "test.tsv";
    private static final String usage = "Uso:\n" +
            "-evaljm <int1-int2> <int3-int4>\n" +
            "-evalbm25 <int1-int2> <int3-int4> (las opciones -evaljm -evalbm25 son mutuamente excluyentes)\n" +
            "-cut <n>: n indica el corte en el ranking para el cómputo de la métrica.\n" +
            "-metrica P | R | MRR | MAP: indica la métrica computada y optimizada en el corte n.\n" +
            "-index <ruta>: ruta de la carpeta que contiene el índice.\n";

    public static void main(String[] args) {

        boolean evaljm = false, evalbm25 = false;
        int cut = 10;
        String evalOption = null;
        String indexDir = null;
        int[] trainingQueries = new int[2];
        int[] testQueries = new int[2];
        String metric = null;


        for (int i = 0; i < args.length; i++) {
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
                    evalArgsBM25 = args[++i].split("-");
                    testQueries[0] = Integer.parseInt(evalArgsBM25[0]);
                    testQueries[1] = Integer.parseInt(evalArgsBM25[1]);
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
            System.err.println("El directorio para el índice \"" + indexPath + "\" no existe o no es un directorio.");
            System.exit(-1);
        }

        // Configurar el analizador
        Analyzer analyzer = new StandardAnalyzer();


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

        } catch (IOException e) {
            System.err.println("Excepción de E/S: " + e.getMessage());
        }
    }


    private static void validateParams(boolean evaljm, boolean evalbm25, int cut, String metrica, String indexPath) {
        if (evaljm && evalbm25) {
            System.err.println("Sólo se acepta evaljm o evalbm25, no ambos.\n");
            System.exit(0);
        } else if (!evaljm && !evalbm25) {
            System.err.println("El valor evaljm o el evalbm25 son obligatorios.\n");
            System.exit(0);
        } else if (cut < 1) {
            System.err.println("Argumento \"cut\" inválido.\n");
            System.exit(0);
        } else if (metrica == null) {
            System.err.println("El argumento \"metrica\" es obligatorio.\n");
            System.exit(0);
        } else if (indexPath == null) {
            System.err.println("El argumento \"index\" es obligatorio.\n");
            System.exit(0);
        } else if (!metrica.equals("P") && !metrica.equals("R") && !metrica.equals("MRR") && !metrica.equals("MAP")) {
            System.err.println("El argumento \"metrica\" no tiene un valor válido.\n");
            System.exit(0);
        }
    }
    //JM model
    private static void evaluateAndOptimizeJMModel(IndexSearcher searcher, Analyzer analyzer,
                                                   int[] trainingQueries, int[] testQueries, int cut,
                                                   String metric) {
        double[] lambdas = {0.001, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
        evaluateAndOptimizeModel(searcher, analyzer, trainingQueries, testQueries, cut, metric, lambdas, "JM");
    }

    //BM25 model
    private static void evaluateAndOptimizeBM25Model(IndexSearcher searcher, Analyzer analyzer,
                                                     int[] trainingQueries, int[] testQueries, int cut,
                                                     String metric) {
        double[] k1Values = {0.4, 0.6, 0.8, 1.0, 1.2, 1.4, 1.6, 1.8, 2.0};
        evaluateAndOptimizeModel(searcher, analyzer, trainingQueries, testQueries, cut, metric, k1Values, "BM25");
    }

    // Evalúa y optimiza un modelo de recuperación de información .
    private static void evaluateAndOptimizeModel(IndexSearcher searcher, Analyzer analyzer,
                                                 int[] trainingRange, int[] testRange, int cut, String metric,
                                                 double[] paramValues, String similarityType) {
        QueryParser parser = new QueryParser("text", analyzer);

        // Leer el archivo de juicios de relevancia (test.tsv)
        File testFile = new File(testFilePath);
        Map<Integer, Map<String, Integer>> relevances = readTsv(testFile);


        // Crear archivos CSV para entrenamiento y test
        File trainingCsvFile = new File("TREC-COVID." + similarityType.toLowerCase()
                + ".training." + trainingRange[0] + "-" + trainingRange[1] + ".test." +
                testRange[0] + "-" + testRange[1] + "." + metric + cut + ".training.csv");
        File testCsvFile = new File("TREC-COVID." + similarityType.toLowerCase() +
                ".training." + trainingRange[0] + "-" + trainingRange[1] + ".test."
                + testRange[0] + "-" + testRange[1] + "." + metric + cut + ".test.csv");

        try {
            // writers para archivos CSV de entrenamiento y test
            PrintWriter trainingWriter = new PrintWriter(trainingCsvFile);
            PrintWriter testWriter = new PrintWriter(testCsvFile);

            // Escribir encabezados en archivos CSV de entrenamiento
            trainingWriter.print(metric + "@" + cut);
            for (double param : paramValues) {
                trainingWriter.print("," + param);
            }
            trainingWriter.print(System.lineSeparator());

            // leer jsonl de queries
            File queryFile = new File(queryFilePath);
            ObjectReader queryReader = JsonMapper.builder().findAndAddModules().build()
                    .readerFor(QueryJsonl.class);
            List<QueryJsonl> trainingQueries = new LinkedList<>();
            MappingIterator<QueryJsonl> itr = queryReader.readValues(queryFile);

            while (itr.hasNext()) {
                QueryJsonl query;
                query = itr.next();
                if (query.id() >= trainingRange[0] && query.id() <= trainingRange[1])
                    trainingQueries.add(query);
            }

            int numTrainingQueries = trainingQueries.size();
            double[][] scoreMatrix = new double[trainingQueries.size()][paramValues.length];
            int row = 0;
            for (QueryJsonl query : trainingQueries) {
                int column = -1;
                for (double param : paramValues) {          // para cada query probamos con todos los valores de k1/lambda
                    column++;

                    searcher.setSimilarity(
                            similarityType.equals("JM")?
                                    new LMJelinekMercerSimilarity((float) param) : new BM25Similarity((float) param, 0.75f)
                    );
                    Query q = parser.parse(query.metadata().query());
                    Map<String, Integer> thisRelevances = relevances.get(query.id());

                    // Ranking de documentos  al hacer una búsqueda
                    TopDocs topDocs = searcher.search(q, cut); // sacamos los top docs para las métricas
                    List<ScoreDoc> scoreDocs = List.of(topDocs.scoreDocs);
                    int relevantQuery = 0;

                    for (String corpusID : thisRelevances.keySet()) {
                        // para cada doc, vemos si es relevante para esta query
                        if (thisRelevances.get(corpusID) > 0)
                            relevantQuery++;
                    }

                    double score = 0;

                    trainingWriter.print(query.id());

                    if (relevantQuery == 0 && column == 0) {    // no tenemos en cuenta para las métricas las queries sin resultados
                        numTrainingQueries--;
                    } else {
                        switch (metric) {
                            case "P":
                                score = calculatePrecision(scoreDocs, searcher, thisRelevances, cut);
                                break;
                            case "R":
                                score = calculateRecall(scoreDocs, searcher, thisRelevances, relevantQuery);
                                break;
                            case "MAP":
                                score = calculateAP(scoreDocs, searcher, thisRelevances, relevantQuery);
                                break;
                            case "MRR":
                                score = calculateRR(scoreDocs, searcher, thisRelevances);
                                break;
                            default:
                                System.err.println("Valor de métrica no válido: " + metric);
                                System.exit(1);
                                break;
                        }
                    }

                    // save & print query metric
                    scoreMatrix[row][column] = score;
                    trainingWriter.print("," + score);
                }
                trainingWriter.print(System.lineSeparator());
                row++;
            }

            // calcular y escribir promedios
            double bestScore = 0;
            int bestColumn = 0;
            trainingWriter.print("Promedios:");
            for(int i = 0; i < paramValues.length; i++) {  // en cada columna calcula el promedio y lo escribe
                double sumScore = 0;
                for (int j = 0; j < trainingQueries.size(); j++) {
                    sumScore += scoreMatrix[j][i];
                }
                double mean = sumScore / numTrainingQueries;    // suma de la columna entre num de queries relevantes de cut
                trainingWriter.print("," + mean);
                if(mean > bestScore) {
                    bestScore = mean;
                    bestColumn = i;
                }
            }
            trainingWriter.print(System.lineSeparator());

            double bestParam = paramValues[bestColumn];

            // TEST ----------------------------------------------------------------------------------------

            List<QueryJsonl> testQueries = new LinkedList<>();
            MappingIterator<QueryJsonl> itr_test = queryReader.readValues(queryFile);

            while (itr_test.hasNext()) {
                QueryJsonl query;
                query = itr_test.next();
                if (query.id() >= testRange[0] && query.id() <= testRange[1])
                    testQueries.add(query);
            }

            int numTestQueries = testQueries.size();
            searcher.setSimilarity(
                    similarityType.equals("JM")?
                            new LMJelinekMercerSimilarity((float) bestParam) : new BM25Similarity((float) bestParam, 0.75f)
            );

            // Escribir encabezado en archivo CSV de test
            String paramName = similarityType.equals("JM")? "lambda = " : "k1 = ";
            testWriter.println(paramName + bestParam + "," + metric + "@" + cut);

            double sumTestScores = 0;

            for (QueryJsonl query : testQueries) {

                Query q = parser.parse(query.metadata().query());
                Map<String, Integer> thisRelevances = relevances.get(query.id());

                // Ranking de documentos  al hacer una búsqueda
                TopDocs topDocs = searcher.search(q, cut); // sacamos los top docs para las métricas
                List<ScoreDoc> scoreDocs = List.of(topDocs.scoreDocs);
                int relevantQuery = 0;

                for (String corpusID : thisRelevances.keySet()) {
                    // para cada doc, vemos si es relevante para esta query
                    if (thisRelevances.get(corpusID) > 0)
                        relevantQuery++;
                }

                double score = 0;

                if (relevantQuery == 0) {    // no tenemos en cuenta para las métricas las queries sin resultados
                    numTestQueries--;
                } else {
                    switch (metric) {
                        case "P":
                            score = calculatePrecision(scoreDocs, searcher, thisRelevances, cut);
                            break;
                        case "R":
                            score = calculateRecall(scoreDocs, searcher, thisRelevances, relevantQuery);
                            break;
                        case "MAP":
                            score = calculateAP(scoreDocs, searcher, thisRelevances, relevantQuery);
                            break;
                        case "MRR":
                            score = calculateRR(scoreDocs, searcher, thisRelevances);
                            break;
                        default:
                            System.err.println("Valor de métrica no válido: " + metric);
                            System.exit(1);
                            break;
                    }
                }

                testWriter.println(query.id() + "," + score);
                sumTestScores += score;
            }

            testWriter.println("Promedio:," + (sumTestScores/numTestQueries));

            // cerrar los writers
            trainingWriter.flush();
            testWriter.flush();
            trainingWriter.close();
            testWriter.close();
        } catch (FileNotFoundException e) {
            System.err.println("Error al crear los writers: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error de E/S: " + e.getMessage());
            System.exit(1);
        } catch (ParseException e) {
            System.err.println("Error de parsing: " + e.getMessage());
            System.exit(1);
        }

        // Leer los archivos CSV recién creados y imprimir su contenido en la consola
        try {
            List<String> trainingCsvLines = Files.readAllLines(trainingCsvFile.toPath());
            List<String> testCsvLines = Files.readAllLines(testCsvFile.toPath());

            System.out.println("Contenido del archivo CSV de entrenamiento:");
            for (String line : trainingCsvLines) {
                System.out.println(line);
            }

            System.out.println("\nContenido del archivo CSV de test:");
            for (String line : testCsvLines) {
                System.out.println(line);
            }
        } catch (IOException e) {
            System.err.println("Error al leer los archivos CSV: " + e.getMessage());
            System.exit(1);
        }

    }

    private static double calculatePrecision(List<ScoreDoc> scoreDocs, IndexSearcher searcher,
                                             Map<String, Integer> thisRelevances, int cut){
        return (getRelevantN(scoreDocs, searcher, thisRelevances) / cut);
    }

    private static double calculateRecall(List<ScoreDoc> scoreDocs, IndexSearcher searcher,
                                          Map<String, Integer> thisRelevances, int relevantQuery){
        return (getRelevantN(scoreDocs, searcher, thisRelevances) / relevantQuery);
    }

    private static double getRelevantN(List<ScoreDoc> scoreDocs, IndexSearcher searcher,
                                       Map<String, Integer> thisRelevances){
        int relevantN = 0;

        for(ScoreDoc scoreDoc : scoreDocs) {        
            // iteramos por el ranking
            // buscamos cada documento de topDocs
            Document doc = null;
            try {
                doc = searcher.doc(scoreDoc.doc);
            } catch (IOException e) {
                System.err.println("Error de E/S: " + e.getMessage());
                System.exit(1);
            }
            String corpusID = doc.get("id");
            int relevance = thisRelevances.getOrDefault(corpusID, 0);

            if(relevance > 0) {
                relevantN++;
            }
        }

        return relevantN;
    }

    private static double calculateAP(List<ScoreDoc> scoreDocs, IndexSearcher searcher,
                                          Map<String, Integer> thisRelevances, int relevantQuery){
        int rankingPos = 0;
        double sumAccuracies = 0;
        int relevantN = 0;

        for(ScoreDoc scoreDoc : scoreDocs) {        // iteramos por el ranking
            rankingPos++;

            // buscamos cada documento de topDocs
            Document doc = null;
            try {
                doc = searcher.doc(scoreDoc.doc);
            } catch (IOException e) {
                System.err.println("Error de E/S: " + e.getMessage());
                System.exit(1);
            }
            String corpusID = doc.get("id");
            int relevance = thisRelevances.getOrDefault(corpusID, 0);

            if(relevance > 0) {
                relevantN++;
                sumAccuracies += (double) relevantN / rankingPos;
            }
        }

        // cálculo de métricas
        return (sumAccuracies / relevantQuery);
    }

    private static double calculateRR(List<ScoreDoc> scoreDocs, IndexSearcher searcher,
                                      Map<String, Integer> thisRelevances) {
        int rankingPos = 0;
        int firstRelevant = 0;

        for(ScoreDoc scoreDoc : scoreDocs) {        // iteramos por el ranking
            rankingPos++;

            // buscamos cada documento de topDocs
            Document doc = null;
            try {
                doc = searcher.doc(scoreDoc.doc);
            } catch (IOException e) {
                System.err.println("Error de E/S: " + e.getMessage());
                System.exit(1);
            }
            String corpusID = doc.get("id");
            int relevance = thisRelevances.getOrDefault(corpusID, 0);

            if(firstRelevant == 0 && relevance > 0)
                firstRelevant = rankingPos;
        }

        return firstRelevant == 0? 0 : (double) 1 /firstRelevant;
    }

    private static Map<Integer, Map<String, Integer>> readTsv(File test) {
        Map<Integer, Map<String, Integer>> data = new HashMap<>();

        try (BufferedReader tsvReader = new BufferedReader(new FileReader(test))) {
            String line = tsvReader.readLine();      // ignoramos la línea de cabecera
            while ((line = tsvReader.readLine()) != null) {
                String[] lineItems = line.split("\t");
                int queryId = tryParse(lineItems[0], "Error en el archivo de tests de relevancia.");

                Map<String, Integer> map = data.containsKey(queryId)? data.get(queryId) : new HashMap<>();
                int relev = tryParse(lineItems[2], "Error en el archivo de tests de relevancia.");
                map.put(lineItems[1], relev);
                data.put(queryId, map);
            }
        } catch (FileNotFoundException e) {
            System.err.println("No se ha encontrado el archivo de tests de relevancia.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Excepción de E/S al leer el archivo de tests de relevancia: " + e.getMessage());
            System.exit(1);
        }
        return data;
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

}
