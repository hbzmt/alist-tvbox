package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.tvbox.IndexContext;
import cn.har01d.alist_tvbox.tvbox.IndexRequest;
import cn.har01d.alist_tvbox.tvbox.Site;
import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.similarity.CosineSimilarity;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class IndexService {
    private final AListService aListService;
    private final AppProperties appProperties;

    public IndexService(AListService aListService, AppProperties appProperties) {
        this.aListService = aListService;
        this.appProperties = appProperties;
        updateIndexFile();
    }

    public void updateIndexFile() {
        for (Site site : appProperties.getSites()) {
            if (site.isSearchable() && StringUtils.hasText(site.getIndexFile())) {
                try {
                    downloadIndexFile(site.getName(), site.getIndexFile(), true);
                } catch (Exception e) {
                    log.warn("", e);
                }
            }
        }
    }

    public String downloadIndexFile(String site, String url) throws IOException {
        return downloadIndexFile(site, url, false);
    }

    public String downloadIndexFile(String site, String url, boolean update) throws IOException {
        String name = getIndexFileName(url);
        String filename = name;
        if (name.endsWith(".zip")) {
            filename = name.replace(".zip", ".txt");
        }

        File file = new File(".cache/" + site + "/" + filename);
        if (update) {
            Files.deleteIfExists(file.toPath());
        } else if (file.exists()) {
            return file.getAbsolutePath();
        }

        log.info("download index file from {}", url);
        if (name.endsWith(".zip")) {
            File zipFile = new File(".cache/" + site + "/" + name);
            FileUtils.copyURLToFile(new URL(url), zipFile);
            unzip(zipFile);
            Files.delete(zipFile.toPath());
        } else {
            FileUtils.copyURLToFile(new URL(url), file);
        }

        return file.getAbsolutePath();
    }

    public static void unzip(File file) throws IOException {
        Path destFolderPath = Paths.get(file.getParent());

        try (ZipFile zipFile = new ZipFile(file, ZipFile.OPEN_READ, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = destFolderPath.resolve(entry.getName());
                if (entryPath.normalize().startsWith(destFolderPath.normalize())) {
                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Files.createDirectories(entryPath.getParent());
                        try (InputStream in = zipFile.getInputStream(entry);
                             OutputStream out = Files.newOutputStream(entryPath.toFile().toPath())) {
                            IOUtils.copy(in, out);
                        }
                    }
                }
            }
        }
    }

    private String getIndexFileName(String url) {
        int index = url.lastIndexOf('/');
        String name = "index.txt";
        if (index > -1) {
            name = url.substring(index + 1);
        }
        if (name.isEmpty()) {
            return "index.txt";
        }
        return name;
    }

    public void index(IndexRequest indexRequest) throws IOException {
        StopWatch stopWatch = new StopWatch("index");
        File dir = new File("data/index/" + indexRequest.getSite());
        Files.createDirectories(dir.toPath());
        File file = new File(dir, indexRequest.getIndexName() + ".txt");
        Files.deleteIfExists(file.toPath());

        try (FileWriter writer = new FileWriter(file, true)) {
            IndexContext context = new IndexContext(indexRequest, writer);
            for (String path : indexRequest.getPaths()) {
                stopWatch.start("index " + path);
                index(context, path, 0);
                stopWatch.stop();
            }
            log.info("index stats: {}", context.stats);
        }

        if (indexRequest.isCompress()) {
            File zipFIle = new File(dir, indexRequest.getIndexName() + ".zip");
            zipFile(file, zipFIle);
        }

        log.info("index done, total time : {} {}", Duration.ofNanos(stopWatch.getTotalTimeNanos()), stopWatch.prettyPrint());
        log.info("index file: {}", file.getAbsolutePath());
    }

    private void zipFile(File file, File output) throws IOException {
        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(output.toPath()));
             FileInputStream fis = new FileInputStream(file)) {
            ZipEntry zipEntry = new ZipEntry(file.getName());
            zipOut.putNextEntry(zipEntry);

            byte[] bytes = new byte[4096];
            int length;
            while ((length = fis.read(bytes)) >= 0) {
                zipOut.write(bytes, 0, length);
            }
        }
    }

    private void index(IndexContext context, String path, int depth) throws IOException {
        if (context.getMaxDepth() > 0 && depth == context.getMaxDepth()) {
            return;
        }

        FsResponse fsResponse = aListService.listFiles(context.getSite(), path, 1, 0);
        if (fsResponse == null) {
            context.stats.errors++;
            return;
        }
        if (context.isExcludeExternal() && fsResponse.getProvider().contains("AList")) {
            return;
        }


        List<String> files = new ArrayList<>();
        for (FsInfo fsInfo : fsResponse.getFiles()) {
            if (fsInfo.getType() == 1) {
                String newPath = fixPath(path + "/" + fsInfo.getName());
                if (exclude(context.getExcludes(), newPath)) {
                    context.stats.excluded++;
                    continue;
                }

                index(context, newPath, depth + 1);
            } else if (isMediaFormat(fsInfo.getName())) {
                String newPath = fixPath(path + "/" + fsInfo.getName());
                if (exclude(context.getExcludes(), newPath)) {
                    context.stats.excluded++;
                    continue;
                }

                context.stats.files++;
                files.add(fsInfo.getName());
            }
        }

        if (files.size() > 0 && !context.contains(path)) {
            context.write(path);
        }

        if (isSimilar(path, files, context.getStopWords())) {
            return;
        }

        for (String name : files) {
            String newPath = fixPath(path + "/" + name);
            if (context.contains(newPath)) {
                continue;
            }
            context.write(newPath);
        }
    }

    private boolean exclude(Set<String> rules, String path) {
        for (String rule : rules) {
            if (rule.startsWith("^") && rule.endsWith("$") && path.equals(rule.substring(1, rule.length() - 1))) {
                return true;
            }
            if (rule.startsWith("^") && path.startsWith(rule.substring(1))) {
                return true;
            }
            if (rule.endsWith("$") && path.endsWith(rule.substring(0, rule.length() - 1))) {
                return true;
            }
            if (path.contains(rule)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMediaFormat(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            String suffix = name.substring(index + 1);
            return appProperties.getFormats().contains(suffix);
        }
        return false;
    }

    private String fixPath(String path) {
        return path.replaceAll("/+", "/").replaceAll("\n", "%20");
    }

    private String getFolderName(String path) {
        int index = path.lastIndexOf('/');
        if (index > 0) {
            return path.substring(index + 1);
        }
        return path;
    }

    public boolean isSimilar(String path, List<String> sentences, Set<String> stopWords) {
        if (sentences.isEmpty()) {
            return true;
        }
        if (sentences.size() == 1) {
            String folderName = getFolderName(path);
            List<String> list = new ArrayList<>(sentences);
            list.add(folderName);
            return isSimilar(path, list, stopWords);
        }

        double sum = 0.0;
        CosineSimilarity cosineSimilarity = new CosineSimilarity();
        Map<CharSequence, Integer> leftVector = getVector(stopWords, sentences.get(0));
        for (int i = 1; i < sentences.size(); ++i) {
            Map<CharSequence, Integer> rightVector = getVector(stopWords, sentences.get(i));
            sum += cosineSimilarity.cosineSimilarity(leftVector, rightVector);
            leftVector = rightVector;
        }
        double result = sum / (sentences.size() - 1);

        log.debug("cosineSimilarity {} : {}", path, result);
        return result > 0.9;
    }

    private Map<CharSequence, Integer> getVector(Set<String> stopWords, String text) {
        Map<CharSequence, Integer> result = new HashMap<>();
        for (String stopWord : stopWords) {
            text = text.replaceAll(stopWord, "");
        }
        text = text.replaceAll("\\d+", " ").replaceAll("\\s+", " ");
        List<Term> termList = HanLP.segment(text);
        for (Term term : termList) {
            int frequency = term.getFrequency();
            if (frequency == 0) {
                frequency = 1;
            }
            if (result.containsKey(term.word)) {
                result.put(term.word, result.get(term.word) + frequency);
            } else {
                result.put(term.word, frequency);
            }
        }
        return result;
    }
}
