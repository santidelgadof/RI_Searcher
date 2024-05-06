package practicari;

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;


import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;


public class IndexTrecCovid {
    private static final String usage = "Uso:\n" +
            "java IndexTrecCovid -openmode <openmode> -index <ruta> -docs <ruta> -indexingmodel <modelo>\n" +
            "  -openmode <openmode>: (el open mode será append, create, o create_or_append)\n" +
            "  -index <ruta>: ruta de la carpeta que contiene o contendrá el índice\n" +
            "  -docs <ruta>: ruta de la carpeta que contiene el corpus de documentos y también los " +
            "archivos de queries y juicios de relevancia\n" +
            "  -indexingmodel cuyos valores posibles son jm <lambda> | bm25 <k1>";

    public static void main(String[] args) {
        // Parsear argumentos de línea de comandos
        String openMode = null;
        String indexPath = null;
        String docsPath = null;
        String indexingModel = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-openmode":
                    openMode = args[++i];
                    break;
                case "-index":
                    indexPath = args[++i];
                    break;
                case "-docs":
                    docsPath = args[++i];
                    break;
                case "-indexingmodel":
                    indexingModel = args[++i];
                    // Asume que el siguiente argumento es el valor numérico asociado al modelo
                    if (indexingModel.equalsIgnoreCase("jm") || indexingModel.equalsIgnoreCase("bm25")) {
                        // Agrega espacio y el valor del siguiente argumento
                        indexingModel += " " + args[++i];
                    }
                    break;
                default:
                    System.err.println("Opción desconocida: " + args[i]);
                    System.out.println(usage);
                    System.exit(1);
            }
        }

        // Validar argumentos
        if (openMode == null || indexPath == null || docsPath == null || indexingModel == null) {
            System.err.println(usage);
            System.exit(1);
        }

        // Configurar el analizador
        Analyzer analyzer = new StandardAnalyzer();

        // Configurar la similitud según el modelo especificado
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        if (indexingModel.startsWith("jm")) {
            double lambda = Double.parseDouble(indexingModel.substring(3));
            iwc.setSimilarity(new LMJelinekMercerSimilarity((float) lambda));
        } else if (indexingModel.startsWith("bm25")) {
            float k1 = Float.parseFloat(indexingModel.substring(5));
            iwc.setSimilarity(new BM25Similarity(k1, 0.75f)); // Usando b=0.75 por defecto
        } else {
            System.err.println("Modelo de indexación no válido: " + indexingModel);
            System.exit(1);
        }

        // Definir el modo de apertura del índice
        if ("create_or_append".equalsIgnoreCase(openMode)) {
            iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
        } else if ("create".equalsIgnoreCase(openMode)) {
            iwc.setOpenMode(OpenMode.CREATE);
        } else if ("append".equalsIgnoreCase(openMode)) {
            iwc.setOpenMode(OpenMode.APPEND);
        } else {
            System.err.println("Modo de apertura no válido: " + openMode);
            System.exit(1);
        }

        // Abrir el índice
        try {
            Directory dir = FSDirectory.open(Paths.get(indexPath));
            IndexWriter writer = new IndexWriter(dir, iwc);

            // Parsear y indexar documentos
            if(!docsPath.endsWith(File.separator))
                docsPath += File.separator;
            File corpus = new File(docsPath + "corpus.jsonl");
            parseAndIndex(corpus, writer);

            writer.commit();
            writer.close();
            dir.close();
        } catch (IOException e) {
            System.err.println("Excepción de E/S: " + e.getMessage());
            System.exit(1);
        }
    }


    private static void parseAndIndex(File corpusFile, IndexWriter writer) {
        ObjectReader reader = JsonMapper.builder().findAndAddModules().build()
                .readerFor(Doc.class);
        final List<Doc> corpusDocs;
        try {
            corpusDocs = reader.<Doc>readValues(corpusFile).readAll();

            // mientras haya documentos, les sacamos las partes y las indexamos
            for (Doc current : corpusDocs) {
                Document doc = new Document();

                doc.add(new KeywordField("id", current.id(), Field.Store.YES));
                doc.add(new StringField("title", current.title(), Field.Store.YES));
                doc.add(new TextField("text", current.text(), Field.Store.YES));
                doc.add(new StringField("url", current.metadata().url(), Field.Store.YES));
                doc.add(new StringField("pubmed_id", current.metadata().pubmed_id(), Field.Store.YES));

                writer.addDocument(doc);
            }
        } catch (IOException e) {
            System.err.println("Error al indexar el archivo corpus.jsonl: " + e.getMessage());
            System.exit(1);
        }

    }

}
