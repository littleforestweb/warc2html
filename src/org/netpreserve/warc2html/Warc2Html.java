/*
 * Copyright 2021 National Library of Australia
 * SPDX-License-Identifier: Apache-2.0
 */
package org.netpreserve.warc2html;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.urlcanon.Canonicalizer;
import org.netpreserve.urlcanon.ParsedUrl;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZoneOffset.UTC;
import static org.netpreserve.warc2html.LinkRewriter.rewriteCSS;
import static org.netpreserve.warc2html.LinkRewriter.rewriteJS;

public class Warc2Html {

    private static final DateTimeFormatter ARC_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.UK).withZone(UTC);
    private static final Map<String, String> DEFAULT_FORCED_EXTENSIONS = loadForcedExtensions();
    private final Map<String, Resource> resourcesByUrlKey = new HashMap<>();
    private final Map<String, Resource> resourcesByPath = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String> forcedExtensions = new HashMap<>(DEFAULT_FORCED_EXTENSIONS);
    private String warcBaseLocation = "";
    private String rejectedPathsFilePath = "";

    public static void main(String[] args) throws IOException {
        System.out.println("Initializing");

        Warc2Html warc2Html = new Warc2Html();
        Path outputDir = Paths.get(".");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h":
                case "--help":
                    System.out.println("Usage: warc2html [-o outdir] file1.warc [file2.warc ...]");
                    System.out.println("       warc2html [-o outdir] -b http://example.org/warcs/ file1.cdx [file2.cdx ...]");
                    return;
                case "-b":
                case "--warc-base":
                    warc2Html.setWarcBaseLocation(args[++i]);
                    break;
                case "-o":
                case "--output-dir":
                    outputDir = Paths.get(args[++i]);
                    break;
                case "-rp":
                case "--rejected-paths":
                    warc2Html.setRejectedPathsFilePath(args[++i]);
                    break;
                case "-wf":
                case "--warc-folder":
                    File[] files = new File((args[++i])).listFiles();
                    assert files != null;
                    Arrays.sort(files);
                    for (int j = 0; j < files.length; j++) {
                        File file = files[j];
                        System.out.println("Load (" + (j + 1) + "/" + files.length + ") - " + file.getName());
                        try (InputStream stream = new FileInputStream(file.getAbsolutePath())) {
                            warc2Html.load(file.getAbsolutePath(), stream);
                        }
                    }
                    break;
                default:
                    System.err.println("warc2html: unknown option: " + args[i]);
                    System.exit(1);
                    return;
            }
        }

        // Run
        JsonArray resourceArray = warc2Html.writeTo(outputDir);

        // Convert JsonArray to JSON and write to a file
        System.out.println("-------------------");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String resources_log_path = outputDir.resolve("_leaf_warc_resources.json").toString();
        try (FileWriter writer = new FileWriter(resources_log_path)) {
            gson.toJson(resourceArray, writer);
            System.out.println("JSON resourceArray file created successfully!");
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("-------------------");
    }

    public static JsonObject loadRejectedPathsFromFile(String filePath) {
        // Initialize Gson
        Gson gson = new Gson();
        JsonObject jsonObject = new JsonObject();

        try {
            // Read the file
            String jsonString = Files.readString(Paths.get(filePath));

            // Parse the JSON string into a JSON object
            jsonObject = gson.fromJson(jsonString, JsonObject.class);

        } catch (IOException e) {
            e.printStackTrace();
        }

        return jsonObject;
    }

    public static boolean isRejectedPath(String inputString, JsonObject rulesObject) {

        // Get startsWith and contains values
        JsonArray startsWithArray = rulesObject.getAsJsonArray("startswith");
        JsonArray containsArray = rulesObject.getAsJsonArray("contains");

        // Check if string starts with
        if (startsWithArray != null) {
            for (int i = 0; i < startsWithArray.size(); i++) {
                String startsWithValue = startsWithArray.get(i).getAsString();
                if (inputString.startsWith(startsWithValue)) {
                    return true;
                }
            }
        }

        // Check if string contains
        if (containsArray != null) {
            for (int i = 0; i < containsArray.size(); i++) {
                String containsValue = containsArray.get(i).getAsString();
                if (inputString.contains(containsValue)) {
                    return true;
                }
            }
        }

        return false;
    }

    public static String makeUrlKey(String url) {
        ParsedUrl parsedUrl = ParsedUrl.parseUrl(url);
        Canonicalizer.AGGRESSIVE.canonicalize(parsedUrl);
        return parsedUrl.toString();
    }

    private static String ensureUniquePath(Map<String, Resource> pathIndex, String path) {
        if (pathIndex.containsKey(path)) {
            String[] basenameAndExtension = PathUtils.splitExtension(path);
            for (long i = 1; pathIndex.containsKey(path); i++) {
                path = basenameAndExtension[0] + "~" + i + basenameAndExtension[1];
            }
        }
        return path;
    }

    private static Map<String, String> loadForcedExtensions() {
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(Warc2Html.class.getResourceAsStream("forced.extensions"), "forced.extensions resource missing")))) {
            var map = new HashMap<String, String>();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.strip().split("\\s+");
                map.put(fields[0], fields[1]);
            }
            return Collections.unmodifiableMap(map);
        } catch (IOException e) {
            throw new RuntimeException("Error loading forced.extensions", e);
        }
    }

    public void setWarcBaseLocation(String warcBaseLocation) {
        this.warcBaseLocation = warcBaseLocation;
    }

    public void setRejectedPathsFilePath(String rejectedPathsFilePath) {
        this.rejectedPathsFilePath = rejectedPathsFilePath;
    }

    private void load(String filename, InputStream stream) throws IOException {
        if (!stream.markSupported()) {
            stream = new BufferedInputStream(stream);
        }
        stream.mark(1);
        int firstByte = stream.read();
        stream.reset();
        if (firstByte == 'W' || firstByte == 0x1f || firstByte == 'f') {
            loadWarc(filename, stream);
        } else {
            loadCdx(new BufferedReader(new InputStreamReader(stream, UTF_8)));
        }
    }

    public void loadCdx(BufferedReader reader) throws IOException {
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            if (line.isBlank() || line.startsWith(" ")) {
                continue;
            }

            String[] fields = line.split(" ");
            Instant instant = ARC_DATE_FORMAT.parse(fields[1], Instant::from);
            String url = fields[2];
            String type = fields[3];
            int status = fields[4].equals("-") ? 0 : Integer.parseInt(fields[4]);
            long length = Long.parseLong(fields[8]);
            long offset = Long.parseLong(fields[9]);
            String warc = fields[11];
            String locationHeader = fields[6];

            add(new Resource(url, instant, status, type, warc, offset, length, locationHeader));
        }
    }

    private void loadWarc(String filename, InputStream stream) throws IOException {
        WarcReader reader = new WarcReader(stream);
        WarcRecord record = reader.next().orElse(null);
        while (record != null) {
            if (!(record instanceof WarcResponse)) {
                record = reader.next().orElse(null);
                continue;
            }
            WarcResponse response = (WarcResponse) record;
            String url = response.target();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                record = reader.next().orElse(null);
                continue;
            }
            Instant instant = response.date();
            String type;
            try {
                type = response.payloadType().base().toString();
            } catch (IllegalArgumentException e) {
                type = "application/octet-stream";
            }
            int status = response.http().status();
            long offset = reader.position();
            String locationHeader = response.http().headers().first("Location").orElse(null);

            record = reader.next().orElse(null);
            long length = reader.position() - offset;

            add(new Resource(url, instant, status, type, filename, offset, length, locationHeader));
        }
    }

    protected WarcReader openWarc(String filename, long offset, long length) throws IOException {
        String pathOrUrl = warcBaseLocation + filename;
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            var connection = (HttpURLConnection) new URL(pathOrUrl).openConnection();
            if (length > 0) {
                connection.addRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1));
            } else if (offset > 0) {
                connection.addRequestProperty("Range", "bytes=" + offset + "-");
            }
            return new WarcReader(connection.getInputStream());
        } else {
            FileChannel channel = FileChannel.open(Paths.get(pathOrUrl));
            channel.position(offset);
            return new WarcReader(channel);
        }
    }

    private void add(Resource resource) {
        String path = PathUtils.pathFromUrl(resource.url, forcedExtensions.get(resource.type));
        path = ensureUniquePath(resourcesByPath, path);

        if (resource.status >= 300) {
            return;
        }

        resource.path = path;
        resourcesByPath.put(path, resource);

        String urlKey = makeUrlKey(resource.url);

        Resource existing = resourcesByUrlKey.get(urlKey);
        boolean keepExisting;

        if (existing == null) {
            keepExisting = false;
        } else if (existing.isRedirect() && !resource.isRedirect()) {
            keepExisting = false;
        } else if (resource.isRedirect() && !existing.isRedirect()) {
            keepExisting = true;
        } else {
            keepExisting = resource.instant.isBefore(existing.instant);
        }

        if (!keepExisting) {
            resourcesByUrlKey.put(urlKey, resource);
        }
    }

    public String getRandomAlphaString(int n) {
        String AlphaNumericString = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvxyz";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            int index = (int) (AlphaNumericString.length() * Math.random());
            sb.append(AlphaNumericString.charAt(index));
        }
        return sb.toString();
    }

    public String removeTilda(String url) {
        url = new StringBuilder(url).reverse().toString();
        for (int i = 0; i <= 9; i++) {
            url = url.replace("lmth." + String.valueOf(i) + "~", "lmth.");
            url = url.replace("egap." + String.valueOf(i) + "~", "lmth.");
        }
        return new StringBuilder(url).reverse().toString();
    }

    public JsonArray writeTo(Path outDir) throws IOException {

        // Load Rejected Paths JSON
        JsonObject rejectedPaths = loadRejectedPathsFromFile(rejectedPathsFilePath);
        System.out.println(rejectedPaths);

        // Create an array of JsonObjects
        JsonArray resourceArray = new JsonArray();

        // Create outDir directory
        Files.createDirectories(outDir);

        // Set counters
        int idx = 0;
        int resourcesSize = resourcesByPath.values().size() - 1;

        // Iterate over every resource
        for (Resource resource : resourcesByPath.values()) {

            // Check if resource is to be rejected -> continue
            if (isRejectedPath(resource.url, rejectedPaths)) {
                continue;
            }

            try (WarcReader reader = openWarc(resource.warc, resource.offset, resource.length)) {

                String progressPercentage = Float.toString((idx * 100.0f) / resourcesSize);
                System.out.println("---------------");
                System.out.println("Progress: " + progressPercentage + "%");

                WarcRecord record = reader.next().orElseThrow();
                if (!(record instanceof WarcResponse)) {
                    throw new IllegalStateException();
                }
                WarcResponse response = (WarcResponse) record;

                Path path = outDir.resolve(URLDecoder.decode(resource.path, UTF_8));
                Files.createDirectories(path.getParent());

                try (OutputStream output = Files.newOutputStream(path)) {
                    InputStream input = response.http().body().stream();
                    if (resource.isRedirect()) {
                        String destination = rewriteLink(resource.locationHeader, URI.create(resource.url), resource.path);

                        if (destination == null) {
                            destination = resource.locationHeader;
                        }

                        output.write(("<!-- Redirected From : " + resource.url + " -->\n").getBytes(UTF_8));
                        if (!destination.isBlank() && !destination.isEmpty()) {
                            output.write(("<meta http-equiv=\"refresh\" content=\"0; url=" + destination + "\">\n").getBytes(UTF_8));
                        } else {
                            output.write(("<!-- Change to HTTPS -->\n").getBytes(UTF_8));
                            String tempUrl = resource.url.replace("http://", "https://");
                            String tempPath = removeTilda(resource.path);
                            destination = rewriteLink(resource.locationHeader, URI.create(tempUrl), tempPath);
                            destination = removeTilda(destination);
                            output.write(("<meta http-equiv=\"refresh\" content=\"0; url=" + destination + "\">\n").getBytes(UTF_8));
                        }
                    } else if (resource.type.equals("text/html")) {
                        URI baseUri = URI.create(resource.url);
                        LinkRewriter.rewriteHTML(input, output, url -> rewriteLink(url, baseUri, resource.path));
                    } else {
                        input.transferTo(output);
                    }

                    if (resource.type.equals("text/css")) {
                        String css = Files.readString(path);
                        URI baseUri = URI.create(resource.url);
                        String rewritten = rewriteCSS(css, url -> rewriteLink(url, baseUri, resource.path));
                        try (FileWriter modFile = new FileWriter(path.toFile())) {
                            modFile.write(rewritten);
                        }
                    }

                    if (resource.type.contains("javascript")) {
                        String js = Files.readString(path);
                        String rndStr = getRandomAlphaString(16);
                        String rewritten = rewriteJS(js, url -> url, rndStr);
                        rewritten = "// -------------------------------------------------------- " + "\n"
                                + "// " + rndStr + "\n"
                                + "var " + rndStr + "_pathname = window.location.pathname;" + "\n"
                                + "var " + rndStr + "_basePath = \"" + resource.path.split("/")[0] + "\";" + "\n"
                                + "var " + rndStr + "_pathname_split = " + rndStr + "_pathname;" + "\n"
                                + "if(" + rndStr + "_pathname.includes(" + rndStr + "_basePath)) {" + rndStr + "_pathname_split = " + rndStr + "_pathname.split(" + rndStr + "_basePath)[1];}" + "\n"
                                + rndStr + "_pathname_split = " + rndStr + "_pathname_split.replace(\"//\", \"/\")" + "\n"
                                + "var " + rndStr + "_foldersNumb = " + rndStr + "_pathname_split.split(\"/\").filter(function(item){if (item !== \"\" && !item.endsWith(\".page\")) {return item;}});" + "\n"
                                + "var " + rndStr + "_relativePath = '';" + "\n"
                                + rndStr + "_foldersNumb.forEach(item => " + rndStr + "_relativePath += '../');" + "\n"
                                + "// -------------------------------------------------------- " + "\n\n"
                                + rewritten;
                        try (FileWriter modFile = new FileWriter(path.toFile())) {
                            modFile.write(rewritten);
                        }
                    }
                }

                // Create a JsonObject
                JsonObject resourceJSON = new JsonObject();
                resourceJSON.addProperty("path", resource.path);
                resourceJSON.addProperty("url", resource.url);
                resourceJSON.addProperty("type", resource.type);
                resourceJSON.addProperty("status", resource.status);
                resourceArray.add(resourceJSON);

                // Show console log
                System.out.println(resourceJSON);

                idx += 1;
            } catch (Exception ex) {
                System.out.println("Exception");
                ex.printStackTrace();
            }
        }

        return resourceArray;
    }

    private String rewriteLink(String url, URI baseUri, String basePath) {

        URI uri;
        try {
            uri = baseUri.resolve(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Resource resource = resourcesByUrlKey.get(makeUrlKey(uri.toString()));
        if (resource == null) {
            return null;
        }
        return PathUtils.relativize(resource.path, basePath);
    }

    public void resolveRedirects() {
        this.resourcesByUrlKey.replaceAll((key, resource) -> {
            if (resource.isRedirect()) {
                return resourcesByUrlKey.getOrDefault(makeUrlKey(resource.locationHeader), resource);
            } else {
                return resource;
            }
        });
    }
}
