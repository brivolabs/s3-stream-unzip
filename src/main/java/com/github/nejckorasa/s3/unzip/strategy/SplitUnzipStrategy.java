package com.github.nejckorasa.s3.unzip.strategy;

import com.amazonaws.services.s3.AmazonS3;
import com.github.nejckorasa.s3.unzip.S3UnzipException;
import com.github.nejckorasa.s3.unzip.UnzipTask;
import com.github.nejckorasa.s3.upload.S3MultipartUpload;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Scanner;

import static com.amazonaws.services.s3.internal.Constants.MB;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Unzips and uploads a file with splitting (sharding) - it creates a 1:n mappings between zipped and unzipped files.
 * <p>
 * Utilizes stream download and multipart upload - unzipping is achieved without keeping all data in memory or writing to disk.
 */
@Slf4j
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SplitUnzipStrategy implements UnzipStrategy {

    public static final String LINE_BREAK = "\n";

    @NonNull
    @With
    private int uploadPartBytesLimit = 20 * MB;

    @NonNull
    @With
    private long fileBytesLimit = 100 * MB;

    @NonNull
    private S3MultipartUpload.Config config = S3MultipartUpload.Config.DEFAULT;

    public SplitUnzipStrategy(@NonNull S3MultipartUpload.Config config) {
        this.config = config;
    }

    @Override
    public void unzip(UnzipTask unzipTask, AmazonS3 s3Client) {
        String filename = unzipTask.filename();
        long compressedSize = unzipTask.compressedSize();
        long size = unzipTask.size();

        String key = unzipTask.key();
        var multipartUpload = new S3MultipartUpload(unzipTask.bucketName(), key, s3Client, config);
        multipartUpload.initializeUpload();

        log.info("Unzipping {}, compressed: {} bytes, extracted: {} bytes to {}", filename, compressedSize, size, key);

        int fileNumber = 1;
        Scanner scanner = new Scanner(unzipTask.inputStream());
        S3MultipartUpload s3MultipartUpload = initializeS3MultipartUpload(s3Client, unzipTask, fileNumber);

        try {
            var outputStream = new ByteArrayOutputStream();

            long allBytesRead = 0;
            long uploadPartBytes = 0;
            long fileBytes = 0;

            String headerLine = null;

            long partNumber = 0;
            boolean newFile = false;

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine() + LINE_BREAK;

                if (headerLine == null) {
                    headerLine = line;
                }

                long bytesRead = 0;

                // write header line if new file
                if (newFile) {
                    bytesRead += writeLine(headerLine, outputStream);
                    newFile = false;
                }

                // write line
                bytesRead += writeLine(line, outputStream);
                fileBytes += bytesRead;
                allBytesRead += bytesRead;

                if (uploadPartBytes < uploadPartBytesLimit) {
                    uploadPartBytes += bytesRead;
                    continue;
                }

                // upload new part
                partNumber += 1;

                // have reached file bytes limit
                if (fileBytes > fileBytesLimit) {
                    log.debug("Uploading final part [{}] for file: {} and shard file number: {} - Read {} bytes out of {} bytes", partNumber, filename, fileNumber, allBytesRead, size);

                    // finalize upload with current file
                    s3MultipartUpload.uploadFinalPart(new ByteArrayInputStream(outputStream.toByteArray()));
                    partNumber = 1;
                    fileNumber += 1;
                    fileBytes = 0;
                    newFile = true;

                    log.info("Unzipped and uploaded file: {} shard file number {} in {} parts", filename, fileNumber, partNumber);

                    // initialize new multipart upload
                    s3MultipartUpload = initializeS3MultipartUpload(s3Client, unzipTask, fileNumber);
                } else {
                    log.debug("Uploading part [{}] for file: {} and shard file number: {} - Read {} bytes out of {} bytes", partNumber, filename, fileNumber, allBytesRead, size);
                    s3MultipartUpload.uploadPart(new ByteArrayInputStream(outputStream.toByteArray()));
                }

                outputStream.reset();
                uploadPartBytes = 0;
            }

            // upload remaining part of output stream as final part
            multipartUpload.uploadFinalPart(new ByteArrayInputStream(outputStream.toByteArray()));
            log.info("Unzipped and uploaded file: {} sharded into {} files", filename, fileNumber);

        } catch (Throwable t) {
            multipartUpload.abort();
            throw new S3UnzipException("Failed to unzip " + filename, t);
        }
    }

    private S3MultipartUpload initializeS3MultipartUpload(AmazonS3 s3Client, UnzipTask unzipTask, int fileNumber) {
        String filenameWithNumber = fileNumber + "-" + unzipTask.filename();
        log.debug("Initializing upload for file: {}", filenameWithNumber);

        var multipartUpload = new S3MultipartUpload(unzipTask.bucketName(), unzipTask.outputPrefix() + filenameWithNumber, s3Client, config);
        multipartUpload.initializeUpload();
        return multipartUpload;
    }

    private long writeLine(String line, ByteArrayOutputStream outputStream) {
        byte[] bytes = line.getBytes(UTF_8);
        int bytesRead = bytes.length;
        outputStream.write(bytes, 0, bytesRead);
        return bytesRead;
    }
}
