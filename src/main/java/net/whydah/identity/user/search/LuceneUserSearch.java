package net.whydah.identity.user.search;

import net.whydah.identity.user.identity.UIBUserIdentity;
import net.whydah.identity.user.identity.UIBUserIdentityRepresentation;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHitCountCollector;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Service
public class LuceneUserSearch {
    private static final Logger logger = LoggerFactory.getLogger(LuceneUserSearch.class);
    protected static final Analyzer ANALYZER = new StandardAnalyzer();  //use LuceneUserIndexer.ANALYZER?
    private static final int MAX_HITS = 2500;
    private final Directory index;


    @Autowired
    public LuceneUserSearch(Directory index) {
        this.index = index;
    }

    public List<UIBUserIdentityRepresentation> search(String queryString) {
        String wildCardQuery = buildWildCardQuery(queryString);
        String[] fields = {
                LuceneUserIndexer.FIELD_FIRSTNAME,
                LuceneUserIndexer.FIELD_LASTNAME,
                LuceneUserIndexer.FIELD_EMAIL,
                LuceneUserIndexer.FIELD_USERNAME,
                LuceneUserIndexer.FIELD_MOBILE
        };
        HashMap<String, Float> boosts = new HashMap<>();
        boosts.put(LuceneUserIndexer.FIELD_FIRSTNAME, 2.5f);
        boosts.put(LuceneUserIndexer.FIELD_LASTNAME, 2f);
        boosts.put(LuceneUserIndexer.FIELD_USERNAME, 1.5f);
      
        MultiFieldQueryParser multiFieldQueryParser = new MultiFieldQueryParser(fields, ANALYZER, boosts);
        multiFieldQueryParser.setAllowLeadingWildcard(true);
        Query q;
        try {
            q = multiFieldQueryParser.parse(wildCardQuery);
        } catch (ParseException e) {
            logger.error("Could not parse wildCardQuery={}. Returning empty search result.", wildCardQuery, e);
            return new ArrayList<>();
        }

        List<UIBUserIdentityRepresentation> result = new ArrayList<>();
        DirectoryReader directoryReader = null;
        try {
            //searcher = new IndexSearcher(index, true);    //http://lucene.472066.n3.nabble.com/IndexSearcher-close-removed-in-4-0-td4041177.html
            directoryReader = DirectoryReader.open(index);
            IndexSearcher searcher = new IndexSearcher(directoryReader);
            TopDocs topDocs = searcher.search(q, MAX_HITS);

            for (ScoreDoc hit : topDocs.scoreDocs) {
                int docId = hit.doc;
                Document d = searcher.doc(docId);
                UIBUserIdentity user = new UIBUserIdentity();
                user.setFirstName(d.get(LuceneUserIndexer.FIELD_FIRSTNAME));
                user.setLastName(d.get(LuceneUserIndexer.FIELD_LASTNAME));
                user.setUid(d.get(LuceneUserIndexer.FIELD_UID));
                user.setUsername(d.get(LuceneUserIndexer.FIELD_USERNAME));
                user.setPersonRef(d.get(LuceneUserIndexer.FIELD_PERSONREF));
                user.setCellPhone(d.get(LuceneUserIndexer.FIELD_MOBILE));
                user.setEmail(d.get(LuceneUserIndexer.FIELD_EMAIL));
                //System.out.println(user.getUsername() + " : " + hit.score);
                result.add(user);
            }
        } catch (IOException e) {
            logger.error("Error when searching.", e);
        } finally {
            if (directoryReader != null) {
                try {
                    directoryReader.close();
                } catch (IOException e) {
                    logger.info("searcher.close() failed. Ignore. {}", e.getMessage());
                }
            }
        }

        return result;
    }

    private String buildWildCardQuery(String queryString) {
        queryString=queryString.replace("_","").trim();
        String[] queryitems = queryString.split(" ");
        StringBuilder strb = new StringBuilder();
        for (String queryitem : queryitems) {
            strb.append(queryitem).append("^2 ");
            strb.append(queryitem).append("* ");
        }
        String wildCardQuery = strb.toString();
        logger.debug("Original query={}, wildcard query= {}", queryString, wildCardQuery);
        return wildCardQuery;
    }
    
    public PaginatedUIBUserIdentityDataList query(int pageNumber, String queryString) {
        String wildCardQuery = buildWildCardQuery(queryString);
        String[] fields = {
                LuceneUserIndexer.FIELD_FIRSTNAME,
                LuceneUserIndexer.FIELD_LASTNAME,
                LuceneUserIndexer.FIELD_EMAIL,
                LuceneUserIndexer.FIELD_USERNAME,
                LuceneUserIndexer.FIELD_MOBILE
        };
        HashMap<String, Float> boosts = new HashMap<>();
        boosts.put(LuceneUserIndexer.FIELD_FIRSTNAME, 2.5f);
        boosts.put(LuceneUserIndexer.FIELD_LASTNAME, 2f);
        boosts.put(LuceneUserIndexer.FIELD_USERNAME, 1.5f);
      
        MultiFieldQueryParser multiFieldQueryParser = new MultiFieldQueryParser(fields, ANALYZER, boosts);
        multiFieldQueryParser.setAllowLeadingWildcard(true);
        Query q;
        try {
            q = multiFieldQueryParser.parse(wildCardQuery);
        } catch (ParseException e) {
            logger.error("Could not parse wildCardQuery={}. Returning empty search result.", wildCardQuery, e);
            return new PaginatedUIBUserIdentityDataList();
        }

        List<UIBUserIdentity> result = new ArrayList<>();
        int hits = 0;
        DirectoryReader directoryReader = null;
        try {
            //searcher = new IndexSearcher(index, true);    //http://lucene.472066.n3.nabble.com/IndexSearcher-close-removed-in-4-0-td4041177.html
            directoryReader = DirectoryReader.open(index);
            IndexSearcher searcher = new IndexSearcher(directoryReader);
            TotalHitCountCollector collector = new TotalHitCountCollector();
            TopDocs topDocs = searcher.search(q, Integer.MAX_VALUE);
            hits = topDocs.totalHits;
            ArrayLocation arrayLocation = Paginator.calculateArrayLocation(hits, pageNumber);
            if(hits>0 && arrayLocation.getStart() !=0){
            	for (int i = arrayLocation.getStart() - 1; i < arrayLocation.getEnd(); i++) {

            		int docId = topDocs.scoreDocs[i].doc;

            		Document d = searcher.doc(docId);
            		UIBUserIdentity user = new UIBUserIdentity();
            		user.setFirstName(d.get(LuceneUserIndexer.FIELD_FIRSTNAME));
            		user.setLastName(d.get(LuceneUserIndexer.FIELD_LASTNAME));
            		user.setUid(d.get(LuceneUserIndexer.FIELD_UID));
            		user.setUsername(d.get(LuceneUserIndexer.FIELD_USERNAME));
            		user.setPersonRef(d.get(LuceneUserIndexer.FIELD_PERSONREF));
            		user.setCellPhone(d.get(LuceneUserIndexer.FIELD_MOBILE));
            		user.setEmail(d.get(LuceneUserIndexer.FIELD_EMAIL));
            		//System.out.println(user.getUsername() + " : " + hit.score);
            		result.add(user);               
            	}
            }
            
          
          
        } catch (IOException e) {
            logger.error("Error when searching.", e);
        } finally {
            if (directoryReader != null) {
                try {
                    directoryReader.close();
                } catch (IOException e) {
                    logger.info("searcher.close() failed. Ignore. {}", e.getMessage());
                }
            }
        }
        
        return new PaginatedUIBUserIdentityDataList(pageNumber, hits, result);

        
    }
}
