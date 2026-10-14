# DataStory Web

A browser-based version of DataStory built with Java 17, Spring Boot, Thymeleaf, Apache Commons CSV and Chart.js. This is a web rewrite of the desktop UI, not a direct deployment of Swing.

## Features
- Upload CSV files (maximum 10 MB)
- Preview up to 100 rows
- Completeness, missing-cell and exact-duplicate checks
- Numeric summaries (mean, median, min/max) and IQR outlier flags
- Most frequent values for categorical columns
- Browser chart for numeric mean/median
- Download a text analysis report

## Run locally
Requirements: JDK 17+ and Maven 3.9+.
```bash
mvn spring-boot:run
```
Open http://localhost:8080

## Package
```bash
mvn clean package
java -jar target/datastory-web-1.0.0.jar
```

## Deploy on Render
1. Push this folder to a new GitHub repository.
2. In Render, choose **New + → Web Service** and connect the repository.
3. Choose Docker runtime (Render should detect the Dockerfile), then deploy.
4. Wait for the deploy to finish and open the generated `onrender.com` URL.

You can also use the included `render.yaml` with Render Blueprint deployment.

## Notes
- Uploaded data is processed in memory and stored in the current user's HTTP session only as analysis results. This demo does not provide login, persistent dataset storage, multi-user workspace isolation guarantees beyond separate sessions, or production-grade privacy/security controls.
- For a public demo, do not upload sensitive or confidential data.
- Chart.js is loaded from a CDN, so the chart needs internet access in the browser.
