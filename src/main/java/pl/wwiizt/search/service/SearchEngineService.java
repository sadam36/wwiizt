package pl.wwiizt.search.service;

import static org.elasticsearch.node.NodeBuilder.nodeBuilder;

import java.io.File;
import java.io.FileFilter;
import java.util.List;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.node.Node;
import org.elasticsearch.search.SearchHit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import pl.wwiizt.ccl.model.ChunkList;
import pl.wwiizt.ccl.service.CclService;
import pl.wwiizt.json.service.JsonService;
import pl.wwiizt.wordnet.WordnetJDBC;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

@Service
public class SearchEngineService {

	public static final String INDEX_NAME = "wiki";
	public static final String TYPE_NAME = "wiki";

	public static final String FIELD_FIRST_SENTENCE_BASE_PLAIN_TEXT = "firstSentenceBasePlainText";
	public static final String FIELD_FIRST_SENTENCE_PLAIN_TEXT = "firstSentencePlainText";
	public static final String FIELD_BASE_PLAIN_TEXT = "basePLainText";
	public static final String FIELD_PLAIN_TEXT = "plainText";

	public static final float BOOST_FIELD_FIRST_SENTENCE_BASE_PLAIN_TEXT = 2.5f;
	public static final float BOOST_FIELD_FIRST_SENTENCE_PLAIN_TEXT = 2;
	public static final float BOOST_FIELD_BASE_PLAIN_TEXT = 1.5f;
	public static final float BOOST_FIELD_PLAIN_TEXT = 1;

	private static final Logger LOGGER = Logger.getLogger(SearchEngineService.class);
	
	private static final String[] STOP_LIST = new String[] { "?", "Co", "CO", "to", "jest", "Czy", "był", "Czym", "Gdzie", "coś", "być"}; 

	@Autowired
	private JsonService jsonService;
	@Autowired
	private CclService cclService;

	private Node node;
	private Client client;

	@PostConstruct
	public void intit() {
		node = nodeBuilder().client(true).node();
		client = node.client();
	}

	public void closeNode() {
		if (node != null) {
			node.close();
		}
	}

	public void index(ChunkList chunkList) {
		Preconditions.checkNotNull(chunkList);

		IndexResponse response = client.prepareIndex(INDEX_NAME, TYPE_NAME, chunkList.getFileName()).setSource(jsonService.getJson(chunkList)).execute()
				.actionGet();
	}

	public void index(File dir, String indexName) {
		Preconditions.checkNotNull(dir);

		BulkRequest bulkRequest = Requests.bulkRequest();

		long time = System.currentTimeMillis();

		if (dir.isDirectory()) {
			File[] files = dir.listFiles(new XmlFileFilter());
			if (files != null) {
				for (File file : files) {
					ChunkList cl = cclService.loadFile(file);

					if (cl != null) {
						IndexRequest indexRequest = Requests.indexRequest(indexName);
						indexRequest.source(jsonService.getJson(cl));
						indexRequest.type(TYPE_NAME);
						indexRequest.id(cl.getFileName());

						bulkRequest.add(indexRequest);
					}
				}

				client.bulk(bulkRequest).actionGet();
				LOGGER.info("Files indexed. Time = " + (System.currentTimeMillis() - time) + "ms");
			}
		}
	}

	public List<String> search(ChunkList cl, String indexName) {
		Preconditions.checkNotNull(cl);

		String query = cl.getPlainText();
		if (!StringUtils.hasText(query)) {
			query = "query";
		}
		for (String word : STOP_LIST) {
			query = query.replace(word, "");
		}
		String baseQuery = cl.getBasePlainText();
		
		if (!StringUtils.hasText(baseQuery)) {
			baseQuery = "query";
		}
		for (String word : STOP_LIST) {
			baseQuery = baseQuery.replace(word, "");
		}
		
		String relatives = getRelatives(baseQuery);
		
		QueryBuilder builder = QueryBuilders.boolQuery()
				.should(QueryBuilders.queryString(baseQuery)
						.field(FIELD_FIRST_SENTENCE_BASE_PLAIN_TEXT)
						.boost(BOOST_FIELD_FIRST_SENTENCE_BASE_PLAIN_TEXT))
				.should(QueryBuilders.queryString(baseQuery)
						.field(FIELD_BASE_PLAIN_TEXT)
						.boost(BOOST_FIELD_BASE_PLAIN_TEXT))
				.should(QueryBuilders.queryString(query)
						.field(FIELD_FIRST_SENTENCE_PLAIN_TEXT)
						.boost(BOOST_FIELD_FIRST_SENTENCE_PLAIN_TEXT))
				.should(QueryBuilders.queryString(query)
						.field(FIELD_PLAIN_TEXT)
						.boost(BOOST_FIELD_PLAIN_TEXT))
				.should(QueryBuilders.queryString(relatives)
						.field(FIELD_BASE_PLAIN_TEXT)
						.boost(BOOST_FIELD_BASE_PLAIN_TEXT));
				
//		client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet(); 
		
		SearchResponse response = client.prepareSearch(indexName)
		        .setTypes(TYPE_NAME)
		        .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
		        .setQuery(builder)
		        .setFrom(0)
		        .setSize(pl.wwiizt.main.Main.MAX_DOCS)
		        .setExplain(true)
		        .execute()
		        .actionGet();


		List<String> result = Lists.newArrayList();
		for (SearchHit sh : response.getHits()) {
			result.add(sh.getId());
		}
		return result;
	}
	
	private String getRelatives(String baseText) {
		StringBuffer sb = new StringBuffer();
		String[] tokens = baseText.split(" ");
		for (String token : tokens) {
			List<String> list = WordnetJDBC.INSTANCE.getAllLexInRelationsExceptAntonims(token);
			for (String s : list) {
				sb.append(s);
				sb.append(" ");
			}
		}
		return sb.toString();
	}

	private class XmlFileFilter implements FileFilter {

		public boolean accept(File pathname) {
			return pathname.getName().endsWith(".xml");
		}
	}

}
