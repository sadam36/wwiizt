package pl.wwiizt.vector.service;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import pl.wwiizt.ccl.model.ChunkList;
import pl.wwiizt.ccl.service.CclService;
import pl.wwiizt.main.Main;
import pl.wwiizt.vector.model.Hint;
import pl.wwiizt.vector.model.IndexHeader;
import pl.wwiizt.vector.model.IndexRecord;
import pl.wwizt.vector.distances.CosineDistance;
import pl.wwizt.vector.distances.Distance;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

@Service
public class VectorSearchService {

	private static final Logger LOGGER = Logger
			.getLogger(VectorSearchService.class);

	@Autowired
	private CclService cclService;

//	@PostConstruct
//	public void test() throws Exception {
//		List<Hint> hints = searchWithoutIndex(
//				new File(
//						"D:\\do szkoły\\Wydobywanie wiedzy i informacji z tekstu\\projectWorkspace\\subwiki-with-questions"),
//				"D:\\do szkoły\\Wydobywanie wiedzy i informacji z tekstu\\projectWorkspace\\subwiki-with-questions\\ccl-Para32jonowa.xml",
//				new CosineDistance(), null);
//		for (Hint hint : hints) {
//			System.out.println(hint);
//		}
//	}

	public void index(File dir) throws IOException {
		Preconditions.checkNotNull(dir);

		String pathToIndexDir = dir.getAbsolutePath() + File.separator
				+ "index" + File.separator;

		long time = System.currentTimeMillis();

		LOGGER.info("Building header");

		IndexHeader header = new IndexHeader();

		if (dir.isDirectory()) {
			File[] files = dir.listFiles(new XmlFileFilter());

			if (files != null) {

				for (File file : files) {
					ChunkList cl = cclService.loadFile(file);

					if (cl != null) {
						String[] tokens = cl.getBasePlainText().split(" ");
						for (String token : tokens) {
							header.addHeader(token);
						}
					}
				}
				FileWriter fw = new FileWriter(new File(pathToIndexDir
						+ "header.csv"));
				fw.write(header.toString());
				fw.close();

				if (LOGGER.isInfoEnabled())
					LOGGER.info("Header build. Time = "
							+ (System.currentTimeMillis() - time) + "ms");
			}
		}

		time = System.currentTimeMillis();

		if (dir.isDirectory()) {
			File[] files = dir.listFiles(new XmlFileFilter());
			if (files != null) {
				FileWriter fw = new FileWriter(new File(pathToIndexDir
						+ "index.csv"));
				for (File file : files) {
					ChunkList cl = cclService.loadFile(file);

					if (cl != null) {
						IndexRecord ir = new IndexRecord();
						ir.parseFromFile(file.getAbsolutePath(),
								cl.getBasePlainText(), header);
						fw.write(ir.toString());
						fw.write("\n");
					}
				}
				fw.close();

				if (LOGGER.isInfoEnabled())
					LOGGER.info("Files indexed. Time = "
							+ (System.currentTimeMillis() - time) + "ms");
			}
		}

	}

	public List<Hint> search(File indexDir, String file, Distance distance) {
		ChunkList cl = cclService.loadFile(file);
		IndexHeader header = readHeader(new File(indexDir.getAbsoluteFile()
				+ File.separator + "header.csv"));
		IndexRecord searchedIR = new IndexRecord();
		searchedIR.parseFromFile(file, cl.getBasePlainText(), header);

		List<Hint> hints = Lists.newArrayList();

		try (Scanner scan = new Scanner(new File(indexDir.getAbsoluteFile()
				+ File.separator + "index.csv"))) {
			while (scan.hasNextLine()) {
				IndexRecord ir = new IndexRecord();
				ir.parseFromCSV(scan.nextLine());
				Hint hint = new Hint();
				hint.setPath(ir.getFilePath());
				hint.setRank(ir.compare(searchedIR, distance));
				hints.add(hint);
			}
		} catch (FileNotFoundException e) {
			LOGGER.error("[search]", e);
		}

		Collections.sort(hints);
		hints = hints.subList(0, Main.MAX_DOCS);
		return hints;
	}

	public List<Hint> searchWithoutIndex(File dir, String path,
			Distance distance, Set<String> stopList) {

		List<Hint> hints = Lists.newArrayList();
		ChunkList clSearched = cclService.loadFile(path);
		String clSearchedString = filterStopList(clSearched.getBasePlainText(),
				stopList);
		
		if (dir.isDirectory()) {
			File[] files = dir.listFiles(new XmlFileFilter());
			if (files != null) {
				long time = System.currentTimeMillis();
				int i = 0;
				for (File file : files) {
					ChunkList cl = cclService.loadFile(file);

					if (cl != null) {

						String clString = filterStopList(cl.getBasePlainText(),
								stopList);

						IndexHeader header = new IndexHeader();
						header.parse(clSearchedString, clString);

						IndexRecord searchedIR = new IndexRecord();
						searchedIR.parseFromFile(path, clSearchedString, header);

						IndexRecord ir = new IndexRecord();
						ir.parseFromFile(file.getName(), clString, header);

						Hint hint = new Hint();
						hint.setPath(ir.getFilePath());
						hint.setRank(ir.compare(searchedIR, distance));
						hints.add(hint);
					}
					i++;
				}

				if (LOGGER.isInfoEnabled())
					LOGGER.info("Files searched. Time = "
							+ (System.currentTimeMillis() - time) + "ms");
			}
		}

		Collections.sort(hints);
		hints = hints.subList(0, Main.MAX_DOCS);
		return hints;
	}

	private String filterStopList(String content, Set<String> stopList) {
		if (stopList == null) {
			return content;
		}
		StringBuilder sb = new StringBuilder();
		for (String s : content.split(" ")) {
			if (!stopList.contains(s.toLowerCase())) {
				sb.append(s);
				sb.append(" ");
			}
		}
		return sb.toString();
	}

	private IndexHeader readHeader(File path) {
		IndexHeader ir = new IndexHeader();
		StringBuilder sb = new StringBuilder();

		try (Scanner scanner = new Scanner(path)) {
			while (scanner.hasNextLine()) {
				sb.append(scanner.nextLine());
				sb.append("\n");
			}
		} catch (FileNotFoundException e) {
			LOGGER.error("[readHeader]", e);
		}

		ir.parse(sb.toString());

		return ir;
	}

	private class XmlFileFilter implements FileFilter {

		public boolean accept(File pathname) {
			return pathname.getName().endsWith(".xml");
		}
	}

}
