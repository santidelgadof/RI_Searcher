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
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    // TODO: tener en cuenta queries con AND y OR
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
            System.out.println("El directorio para el índice \"" + indexPath + "\" no existe o no es un directorio.");
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
        } catch (ParseException e) {
            System.err.println("Error de parsing: " + e.getMessage());
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
    //JM model
    private static void evaluateAndOptimizeJMModel(IndexSearcher searcher, Analyzer analyzer,
                                                   int[] trainingQueries, int[] testQueries, int cut,
                                                   String metric) throws IOException, ParseException {
        double[] lambdas = {0.001, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
        evaluateAndOptimizeModel(searcher, analyzer, trainingQueries, testQueries, cut, metric, lambdas, "JM");
    }

    //BM25 model
    private static void evaluateAndOptimizeBM25Model(IndexSearcher searcher, Analyzer analyzer,
                                                     int[] trainingQueries, int[] testQueries, int cut,
                                                     String metric) throws IOException, ParseException {
        double[] k1Values = {0.4, 0.6, 0.8, 1.0, 1.2, 1.4, 1.6, 1.8, 2.0};
        evaluateAndOptimizeModel(searcher, analyzer, trainingQueries, testQueries, cut, metric, k1Values, "BM25");
    }

    // Evalúa y optimiza un modelo de recuperación de información .
    private static void evaluateAndOptimizeModel(IndexSearcher searcher, Analyzer analyzer,
                                                 int[] trainingRange, int[] testRange, int cut, String metric,
                                                 double[] paramValues, String similarityType)
            throws IOException, ParseException {
        QueryParser parser = new QueryParser("text", analyzer);
        //Query[] trainingqueries = parseQuery(parser, trainingQueries[0], trainingQueries[1]);
        Query[] testqueries = parseQuery(parser, testRange[0], testRange[1]);
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

        // FileWriter para archivos CSV de entrenamiento y test
        PrintWriter trainingWriter = new PrintWriter(trainingCsvFile);
        PrintWriter testWriter = new PrintWriter(testCsvFile);

        // Escribir encabezados en archivos CSV de entrenamiento y test
        trainingWriter.print(metric + "@" + cut);
        // TODO: en test, la primera fila primera columna para indicar el valor del lambda en test (lamda óptimo en training),
        //testWriter.write(metric + "@" + cut);
        for (double param : paramValues) {
            trainingWriter.print("," + param);
        }
        trainingWriter.print(System.lineSeparator());
        //testWriter.write(metric + ",\n");

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

        for (QueryJsonl query : trainingQueries) {
            int column = -1;
            for (double param : paramValues) {          // para cada query probamos con todos los valores de k1/lambda
                column++;
                // TODO: analizar que todas las queries se hagan bien con el parser
                searcher.setSimilarity(
                        similarityType.equals("JM")?
                                new LMJelinekMercerSimilarity((float) param) : new BM25Similarity((float) param, 0.75f)
                );      // TODO el searcher se puede inicializar aquí
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
                double sumScore = 0;

                trainingWriter.print(query.id());

                if (relevantQuery == 0) {    // no tenemos en cuenta para las métricas las queries sin resultados
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
                scoreMatrix[query.id() - 1][column] = score;
                trainingWriter.print("," + score);
            }
            trainingWriter.print(System.lineSeparator());
        }

        // calcular y escribir promedios
        double sumScore = 0;
        double bestScore = 0;
        float bestColumn = 0;
        for(int i = 0; i < paramValues.length; i++) {  // en cada columna calcula el promedio y lo escribe
            for (int j = 0; j < trainingQueries.size(); j++) {
                sumScore += scoreMatrix[j][i];
            }
            double mean = sumScore / numTrainingQueries;    // suma de la columna entre num de queries relevantes de cut
            trainingWriter.print("," + mean);
            if(mean > bestScore) {
                bestScore = mean;
                bestColumn = i+1;   // +1 porque la primera columna es la del query id
            }
        }
        trainingWriter.print(System.lineSeparator());

// *****************************************************************************************************************************

        // Procesar cada consulta de test
        /*for (int i = testRange[0]; i <= testRange[1]; i++) {
            Query query = testqueries[i - testRange[0]]; // Obtener la consulta correspondiente
            double score = calculateScore(query, relevances.get(i), searcher, cut, metric);
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

        System.out.println("Best " + similarityType + " Value: " + bestParamValue);*/


        trainingWriter.close();
        testWriter.close();
    }

    private static double calculatePrecision(List<ScoreDoc> scoreDocs, IndexSearcher searcher,
                                             Map<String, Integer> thisRelevances, int cut) throws IOException {
        return getRelevantN(scoreDocs, searcher, thisRelevances) / cut;
    }

    private static double calculateRecall(List<ScoreDoc> scoreDocs, IndexSearcher searcher,
                                          Map<String, Integer> thisRelevances, int relevantQuery) throws IOException {
        return getRelevantN(scoreDocs, searcher, thisRelevances) / relevantQuery;
    }

    private static double getRelevantN(List<ScoreDoc> scoreDocs, IndexSearcher searcher,
                                       Map<String, Integer> thisRelevances) throws IOException {
        int relevantN = 0;

        for(ScoreDoc scoreDoc : scoreDocs) {        // iteramos por el ranking
            // buscamos cada documento de topDocs
            Document doc = searcher.doc(scoreDoc.doc);
            String corpusID = doc.get("id");
            int relevance = thisRelevances.getOrDefault(corpusID, 0);

            if(relevance > 0) {
                relevantN++;
            }
        }

        return (double) relevantN;
    }

    private static double calculateAP(List<ScoreDoc> scoreDocs, IndexSearcher searcher,
                                          Map<String, Integer> thisRelevances, int relevantQuery) throws IOException {
        int rankingPos = 0;
        double sumAccuracies = 0;
        int relevantN = 0;

        for(ScoreDoc scoreDoc : scoreDocs) {        // iteramos por el ranking
            rankingPos++;

            // buscamos cada documento de topDocs
            Document doc = searcher.doc(scoreDoc.doc);
            String corpusID = doc.get("id");
            int relevance = thisRelevances.getOrDefault(corpusID, 0);

            if(relevance > 0) {
                relevantN++;
                sumAccuracies += (double) relevantN / rankingPos;
            }
        }

        // cálculo de métricas
        return sumAccuracies / relevantQuery;
    }

    private static double calculateRR(List<ScoreDoc> scoreDocs, IndexSearcher searcher,
                                      Map<String, Integer> thisRelevances) throws IOException {
        int rankingPos = 0;
        int firstRelevant = 0;

        for(ScoreDoc scoreDoc : scoreDocs) {        // iteramos por el ranking
            rankingPos++;

            // buscamos cada documento de topDocs
            Document doc = searcher.doc(scoreDoc.doc);
            String corpusID = doc.get("id");
            int relevance = thisRelevances.getOrDefault(corpusID, 0);

            if(firstRelevant == 0 && relevance > 0)     // TODO: optimizar
                firstRelevant = rankingPos;
        }

        return firstRelevant == 0? 0 : (double) 1 /firstRelevant;
    }

    public static double calculateScore(Query query, Map<String, Integer> relevanceJudgments, IndexSearcher searcher, int cut, String metric) throws IOException {


        // Ranking de documentos al hacer una búsqueda
        TopDocs topDocs = searcher.search(query, cut);
        List<ScoreDoc> retrievedDocs = Arrays.asList(topDocs.scoreDocs);

        // Imprimir los argumentos recibidos
        System.out.println("Query: " + query );
        System.out.println(" \n"  );
        System.out.println("Relevance Judgments: " + relevanceJudgments);
        System.out.println(" \n"  );
        System.out.println("RetrievedDocs: " + retrievedDocs);



        // Calcular el número total de documentos relevantes para esta consulta
        int totalRelevantDocs = 0;
        for (Map.Entry<String, Integer> entry : relevanceJudgments.entrySet()) {
            if (entry.getValue() > 0) {
                totalRelevantDocs++;
            }
        }

        // Calcular el puntaje según la métrica especificada
        double score = 0.0;
        switch(metric) {
            case "P":
                score = calculatePrecision(retrievedDocs, relevanceJudgments, cut, searcher);
                break;
            case "R":
                score = calculateRecall(retrievedDocs, relevanceJudgments, totalRelevantDocs, searcher);
                break;
            case "MRR":
                score = calculateMRR(retrievedDocs, relevanceJudgments, searcher);
                break;
            case "MAP":
                score = calculateMAP(retrievedDocs, relevanceJudgments, searcher);
                break;
            default:
                System.out.println("Métrica no válida");
                break;
        }

        return score;
    }



    private static double calculatePrecision(List<ScoreDoc> retrievedDocs, Map<String, Integer> relevanceJudgments, int cut, IndexSearcher searcher) throws IOException {
        int relevantRetrieved = 0;
        int retrieved = Math.min(cut, retrievedDocs.size()); // Limitamos a la cantidad de documentos en el corte

        // Verificar si retrievedDocs no está vacío
        if (retrievedDocs.isEmpty()) {
            System.out.println("No se han recuperado documentos.");
            return 0.0;
        }

        for (int i = 0; i < retrieved; i++) {
            ScoreDoc scoreDoc = retrievedDocs.get(i);
            Document doc = searcher.doc(scoreDoc.doc);
            String docId = doc.get("id");
            // Verificar si el documento es relevante y está presente en relevanceJudgments
            if (relevanceJudgments.containsKey(docId) && relevanceJudgments.get(docId) > 0) {
                relevantRetrieved++;
                System.out.println("Documento relevante encontrado: " + docId);
            } else {
                System.out.println("Documento no relevante: " + docId);
            }
        }
        double precision = (double) relevantRetrieved / retrieved;
        System.out.println("Precision calculada: " + precision);
        return precision;
    }



    private static double calculateRecall(List<ScoreDoc> retrievedDocs, Map<String, Integer> relevanceJudgments, int totalRelevantDocs, IndexSearcher searcher) throws IOException {
        int relevantRetrieved = 0;
        for (ScoreDoc scoreDoc : retrievedDocs) {
            Document doc = searcher.doc(scoreDoc.doc);
            String docId = doc.get("id");
            if (relevanceJudgments.containsKey(docId) && relevanceJudgments.get(docId) > 0) {
                relevantRetrieved++;
            }
        }
        return (double) relevantRetrieved / totalRelevantDocs;
    }

    private static double calculateMRR(List<ScoreDoc> retrievedDocs, Map<String, Integer> relevanceJudgments, IndexSearcher searcher) throws IOException {
        for (int i = 0; i < retrievedDocs.size(); i++) {
            ScoreDoc scoreDoc = retrievedDocs.get(i);
            Document doc = searcher.doc(scoreDoc.doc);
            String docId = doc.get("id");
            if (relevanceJudgments.containsKey(docId) && relevanceJudgments.get(docId) > 0) {
                return 1.0 / (i + 1); // Reciprocal rank
            }
        }
        return 0.0; // No se encontraron documentos relevantes
    }

    private static double calculateMAP(List<ScoreDoc> retrievedDocs, Map<String, Integer> relevanceJudgments, IndexSearcher searcher) throws IOException {
        double sumPrecision = 0.0;
        int relevantRetrieved = 0;
        for (int i = 0; i < retrievedDocs.size(); i++) {
            ScoreDoc scoreDoc = retrievedDocs.get(i);
            Document doc = searcher.doc(scoreDoc.doc);
            String docId = doc.get("id");
            if (relevanceJudgments.containsKey(docId) && relevanceJudgments.get(docId) > 0) {
                relevantRetrieved++;
                sumPrecision += (double) relevantRetrieved / (i + 1); // Precision at this position
            }
        }
        return sumPrecision / Math.min(relevanceJudgments.size(), retrievedDocs.size());
    }


    private static void printResults(File file) throws IOException {
        System.out.println("Results for " + file.getName() + ":");
        List<String> lines = Files.readAllLines(file.toPath());
        for (String line : lines) {
            System.out.println(line);
        }
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

    private static Query[] parseQuery(QueryParser parser, int num1, int num2) throws IOException, ParseException {
        List<Query> queries = new ArrayList<>();

        try (LineNumberReader reader = new LineNumberReader(new FileReader(queryFilePath))) {
            String line;
            ObjectMapper objectMapper = new ObjectMapper();

            while ((line = reader.readLine()) != null) {
                // Convertir la línea JSON a un objeto JSON
                JsonNode jsonNode = objectMapper.readTree(line);

                // Extraer el campo 'text' que contiene la consulta
                String queryText = jsonNode.get("text").asText();

                // Parsear la consulta utilizando el QueryParser de Lucene
                Query query = parser.parse(queryText.toLowerCase(Locale.ROOT));

                // Agregar la consulta al array de consultas
                queries.add(query);
            }
        }

        // Convertir la lista de consultas a un array y devolverlo
        return queries.toArray(new Query[0]);
    }

}
