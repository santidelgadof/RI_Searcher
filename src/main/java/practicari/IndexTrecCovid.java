package practicari;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;



public class IndexTrecCovid {

    public static void main(String[] args) throws Exception {
        // Parsear argumentos de línea de comandos
        String openMode = null;
        String indexPath = null;
        String docsPath = null;
        String indexingModel = null;

        for (int i = 0; i < args.length; i++) {
            if ("-openmode".equals(args[i])) {
                openMode = args[++i];
            } else if ("-index".equals(args[i])) {
                indexPath = args[++i];
            } else if ("-docs".equals(args[i])) {
                docsPath = args[++i];
            } else if ("-indexingmodel".equals(args[i])) {
                indexingModel = args[++i];
            }
        }

        // Validar argumentos
        if (openMode == null || indexPath == null || docsPath == null || indexingModel == null) {
            System.err.println("Uso: java IndexTrecCovid -openmode <openmode> -index <ruta> -docs <ruta> -indexingmodel <modelo>");
            System.exit(1);
        }

        // Configurar el analizador
        Analyzer analyzer = new StandardAnalyzer();

        // Configurar la similitud según el modelo especificado
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        if (indexingModel.startsWith("jm")) {
            double lambda = Double.parseDouble(indexingModel.substring(2));
            iwc.setSimilarity(new LMJelinekMercerSimilarity((float) lambda));
        } else if (indexingModel.startsWith("bm25")) {
            float k1 = Float.parseFloat(indexingModel.substring(4));
            iwc.setSimilarity(new BM25Similarity(k1, 0.75f)); // Usando b=0.75 por defecto
        } else {
            System.err.println("Modelo de indexación no válido: " + indexingModel);
            System.exit(1);
        }

        // Definir el modo de apertura del índice
        if ("append".equalsIgnoreCase(openMode)) {
            iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
        } else if ("create".equalsIgnoreCase(openMode)) {
            iwc.setOpenMode(OpenMode.CREATE);
        } else {
            System.err.println("Modo de apertura no válido: " + openMode);
            System.exit(1);
        }

        // Abrir el índice
        Directory dir = FSDirectory.open(Paths.get(indexPath));
        IndexWriter writer = new IndexWriter(dir, iwc);

        // Parsear y indexar documentos
        File docsDir = new File(docsPath);
        indexDocuments(docsDir, writer);
        
        // Parsear las queries
        File queriesFile = new File(docsPath, "queries.jsonl");
        List<String> queries = parseQueries(queriesFile);
        
        // Utilizar las queries según sea necesario
        for (String query : queries) {
            // Aquí puedes realizar la recuperación de información usando cada query
            System.out.println("Query: " + query);
            // Tu lógica de recuperación de información aquí...
        }

        // Cerrar el indexador
        writer.close();
    }

    private static void indexDocuments(File docsDir, IndexWriter writer) throws Exception {
        for (File file : docsDir.listFiles()) {
            if (file.getName().endsWith(".jsonl")) {
                parseAndIndexDocuments(file, writer);
            }
        }
    }

    private static void parseAndIndexDocuments(File file, IndexWriter writer) throws Exception {
        BufferedReader br = new BufferedReader(new FileReader(file));
        String line;
        ObjectMapper mapper = new ObjectMapper();

        while ((line = br.readLine()) != null) {
            JsonNode jsonNode = mapper.readTree(line);

            String id = jsonNode.get("id").asText();
            String title = jsonNode.get("title").asText();
            String text = jsonNode.get("text").asText();
            String url = jsonNode.get("url").asText();
            String pubmedId = jsonNode.get("pubmed_id").asText();

            Document doc = new Document();
            doc.add(new StringField("id", id, Field.Store.YES));
            doc.add(new TextField("title", title, Field.Store.YES));
            doc.add(new TextField("text", text, Field.Store.YES));
            doc.add(new StringField("url", url, Field.Store.YES));
            doc.add(new StringField("pubmed_id", pubmedId, Field.Store.YES));

            writer.addDocument(doc);
        }

        br.close();
    }
    
    private static List<String> parseQueries(File queriesFile) throws Exception {
        List<String> queries = new ArrayList<>();
        BufferedReader br = new BufferedReader(new FileReader(queriesFile));
        String line;
        ObjectMapper mapper = new ObjectMapper();
    
        while ((line = br.readLine()) != null) {
            JsonNode jsonNode = mapper.readTree(line);
            String queryText = jsonNode.get("query").asText();
            queries.add(queryText);
        }
    
        br.close();
        return queries;
    }
    
}
