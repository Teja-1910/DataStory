package com.datastory.web;

import org.apache.commons.csv.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalysisService {
  @SuppressWarnings("null")
  public Map<String,Object> analyze(MultipartFile file) throws IOException {
    if (file == null || file.isEmpty()) throw new IOException("Choose a non-empty CSV file.");
    if (file.getSize() > 10 * 1024 * 1024) throw new IOException("File is too large. Maximum upload size is 10 MB.");
    List<String> headers;
    List<List<String>> rows = new ArrayList<>();
    try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
         CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get().parse(reader)) {
      headers = new ArrayList<>(parser.getHeaderNames());
      if (headers.isEmpty() || headers.stream().anyMatch(h -> h == null || h.isBlank()))
        throw new IOException("CSV must contain non-empty column headers.");
      Set<String> unique = new HashSet<>();
      for (String h : headers) if (!unique.add(h.trim().toLowerCase(Locale.ROOT))) throw new IOException("Duplicate column header: " + h);
      for (CSVRecord record : parser) {
        List<String> row = new ArrayList<>();
        for (int i=0; i<headers.size(); i++) row.add(i < record.size() ? record.get(i).trim() : "");
        rows.add(row);
      }
    } catch (IllegalArgumentException ex) { throw new IOException("Could not parse the CSV. Check its format and headers."); }
    if (rows.isEmpty()) throw new IOException("The CSV has headers but no data rows.");
    List<String> quality = new ArrayList<>();
    Map<String,Integer> missingByColumn = new LinkedHashMap<>();
    for (int c=0;c<headers.size();c++) {
      final int col=c;
      int missing=(int)rows.stream().filter(r -> r.get(col).isBlank()).count();
      missingByColumn.put(headers.get(c),missing);
      if (missing>0) quality.add(headers.get(c)+": "+missing+" missing value(s)");
    }
    Set<String> seen = new HashSet<>(); int duplicates=0;
    for (List<String> row:rows) if (!seen.add(String.join("\u001f",row))) duplicates++;
    List<Map<String,Object>> stats = new ArrayList<>();
    Map<String,Map<String,Long>> categories = new LinkedHashMap<>();
    for (int c=0;c<headers.size();c++) {
      String header=headers.get(c); final int col=c;
      List<Double> nums=rows.stream().map(r -> parseNumber(r.get(col))).filter(Objects::nonNull).sorted().toList();
      if (nums.size() >= Math.max(3, (int)(rows.size()*0.6))) {
        double mean=nums.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median=median(nums);
        double q1=percentile(nums,0.25), q3=percentile(nums,0.75), iqr=q3-q1;
        long outliers=nums.stream().filter(n -> n < q1-1.5*iqr || n > q3+1.5*iqr).count();
        Map<String,Object> s=new LinkedHashMap<>(); s.put("name",header);s.put("type","Numeric");s.put("count",nums.size());
        s.put("mean",round(mean));s.put("median",round(median));s.put("min",nums.get(0));s.put("max",nums.get(nums.size()-1));s.put("outliers",outliers);stats.add(s);
        if(outliers>0) quality.add(header+": "+outliers+" potential outlier(s) by IQR rule");
      } else {
        Map<String,Long> freq=rows.stream().map(r -> r.get(col)).filter(v -> !v.isBlank()).collect(Collectors.groupingBy(v->v,LinkedHashMap::new,Collectors.counting()));
        categories.put(header,freq);
        Map.Entry<String,Long> top=freq.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        Map<String,Object> s=new LinkedHashMap<>();s.put("name",header);s.put("type","Category/Text");s.put("count",rows.size()-missingByColumn.get(header));
        s.put("top",top==null?"—":top.getKey());s.put("topCount",top==null?0:top.getValue());stats.add(s);
      }
    }
    List<List<String>> preview=rows.stream().limit(100).toList();
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("filename",file.getOriginalFilename()); result.put("headers",headers);result.put("rows",preview);result.put("allRows",rows);result.put("totalRows",rows.size());
    result.put("columns",headers.size());result.put("missingTotal",missingByColumn.values().stream().mapToInt(Integer::intValue).sum());
    result.put("missingByColumn",missingByColumn);result.put("duplicates",duplicates);result.put("quality",quality);result.put("stats",stats);
    result.put("completeness",round(100.0*(rows.size()*headers.size()-(int)result.get("missingTotal"))/(rows.size()*headers.size())));
    result.put("categories",categories);
    return result;
  }
  private Double parseNumber(String s) { if(s==null||s.isBlank())return null; try{return Double.parseDouble(s.replace(",","").replace("$","").replace("₹","").trim());}catch(Exception e){return null;} }
  private double median(List<Double> v){return percentile(v,.5);}
  private double percentile(List<Double> v,double p){if(v.isEmpty())return 0; double idx=p*(v.size()-1);int lo=(int)Math.floor(idx),hi=(int)Math.ceil(idx);return v.get(lo)+(v.get(hi)-v.get(lo))*(idx-lo);}
  private double round(double v){return Math.round(v*100.0)/100.0;}
}
