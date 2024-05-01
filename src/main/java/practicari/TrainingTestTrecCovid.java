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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.LineNumberReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private static void evaluateAndOptimizeJMModel(IndexSearcher searcher, Analyzer analyzer, int[] trainingQueries, int[] testQueries, int cut, String metric) throws IOException, ParseException {
        double[] lambdas = {0.001, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
        evaluateAndOptimizeModel(searcher, analyzer, trainingQueries, testQueries, cut, metric, lambdas, "JM");
        System.out.println("training: " + trainingQueries);
    }
    
    //BM25 model
    private static void evaluateAndOptimizeBM25Model(IndexSearcher searcher, Analyzer analyzer, int[] trainingQueries, int[] testQueries, int cut, String metric) throws IOException, ParseException {
        double[] k1Values = {0.4, 0.6, 0.8, 1.0, 1.2, 1.4, 1.6, 1.8, 2.0};
        evaluateAndOptimizeModel(searcher, analyzer, trainingQueries, testQueries, cut, metric, k1Values, "BM25");    
    }

    // Evalúa y optimiza un modelo de recuperación de información .
    private static void evaluateAndOptimizeModel(IndexSearcher searcher, Analyzer analyzer, int[] trainingQueries, int[] testQueries, int cut, String metric, double[] paramValues, String similarityType) throws IOException, ParseException {
        double bestParamValue = 0.0;
        double bestScore = Double.MIN_VALUE;
        double totalScore = 0.0;
        int totalQueries = 0;
        QueryParser parser = new QueryParser("text", analyzer);
        Query[] trainingqueries = parseQuery(parser, trainingQueries[0], trainingQueries[1]);
        Query[] testqueries = parseQuery(parser, testQueries[0], testQueries[1]);
        // Leer el archivo de juicios de relevancia (test.tsv)
        File testFile = new File(testFilePath);
        File queryFile = new File(queryFilePath);
        Map<Integer, Map<String, Integer>> relevances = readTsv(testFile);
        ObjectReader queryReader = JsonMapper.builder().findAndAddModules().build()
                    .readerFor(QueryJsonl.class);
        List<QueryJsonl> queries = new LinkedList<>();
        MappingIterator<QueryJsonl> itr_train = queryReader.readValues(queryFile);
        MappingIterator<QueryJsonl> itr_test = queryReader.readValues(queryFile);

        QueryJsonl query;            

        

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
        while (itr_train.hasNext()) {
            query = itr_train.next();
            if (query.id() >= trainingQueries[0] && query.id() <= trainingQueries[1]) {
                queries.add(query);
                trainingWriter.write(query.id() + ",");
                double[] scores = new double[paramValues.length];
                for (int j = 0; j < paramValues.length; j++) {
                    if (similarityType.equals("JM")) {
                        searcher.setSimilarity(new LMJelinekMercerSimilarity((float) paramValues[j]));
                    } else if (similarityType.equals("BM25")) {
                        BM25Similarity similarity = new BM25Similarity((float) paramValues[j], 0.75F);
                        searcher.setSimilarity(similarity);
                    }
                    double score = calculateScore(query, relevances.get(query.id()), searcher, cut, metric, parser);
                    scores[j] = score;
                    if (score > bestScore) {
                        bestScore = score;
                        bestParamValue = paramValues[j];
                    }
                }
                for (double score : scores) {
                    try {
                        trainingWriter.write(score + ",");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                trainingWriter.write("\n");
            }
        }
        // Procesar cada consulta de test
        while (itr_test.hasNext()) {
            query = itr_test.next();
            if (query.id() >= testQueries[0] && query.id() <= testQueries[1]) {
                queries.add(query);
            
                double score = calculateScore(query, relevances.get(query.id()), searcher, cut, metric, parser);
                testWriter.write(query.id() + ", " + score + "\n");
                totalScore += score;
                totalQueries++;
            }    
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
    
    
    public static double calculateScore(QueryJsonl query, Map<String, Integer> relevanceJudgments, IndexSearcher searcher, int cut, String metric, QueryParser parser  ) throws IOException {
    
        Query q=null;
        try {
            q = parser.parse(query.metadata().query());
        } catch (ParseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        // Ranking de documentos al hacer una búsqueda
        TopDocs topDocs = searcher.search(q, cut);
        List<ScoreDoc> retrievedDocs = Arrays.asList(topDocs.scoreDocs);
        
        // Imprimir los argumentos recibidos
        //System.out.println("Query: " + query );
        //System.out.println(" \n"  );
        //System.out.println("Relevance Judgments: " + relevanceJudgments);
        //System.out.println(" \n"  );
        //System.out.println("RetrievedDocs: " + retrievedDocs);



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
        //System.out.println("Score: " + score);
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
                //System.out.println("Documento relevante encontrado: " + docId);
            } else {
                //System.out.println("Documento no relevante: " + docId);
            }
        }
        double precision = (double) relevantRetrieved / retrieved;
        //System.out.println("Precision calculada: " + precision);
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