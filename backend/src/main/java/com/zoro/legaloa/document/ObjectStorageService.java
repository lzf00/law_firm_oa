package com.zoro.legaloa.document;

import com.zoro.legaloa.common.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ObjectStorageService {
    private final MinioClient internalClient;
    private final MinioClient publicClient;
    private final String bucket;

    public ObjectStorageService(
            @Qualifier("storageInternalClient") MinioClient internalClient,
            @Qualifier("storagePublicClient") MinioClient publicClient,
            @Value("${app.storage.bucket}") String bucket
    ) {
        this.internalClient = internalClient;
        this.publicClient = publicClient;
        this.bucket = bucket;
    }

    @PostConstruct
    void ensurePrivateBucket() {
        try {
            boolean exists = internalClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                internalClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception exception) {
            throw storageUnavailable(exception);
        }
    }

    public String presignedUpload(String objectKey, String contentType) {
        try {
            return publicClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(15, TimeUnit.MINUTES)
                            .extraHeaders(Map.of("Content-Type", contentType))
                            .build()
            );
        } catch (Exception exception) {
            throw storageUnavailable(exception);
        }
    }

    public String presignedDownload(String objectKey) {
        try {
            return publicClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(5, TimeUnit.MINUTES)
                            .build()
            );
        } catch (Exception exception) {
            throw storageUnavailable(exception);
        }
    }

    public StoredObject stat(String objectKey) {
        try {
            StatObjectResponse response = internalClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectKey).build()
            );
            return new StoredObject(response.size(), response.etag(), response.contentType());
        } catch (Exception exception) {
            throw new BusinessException(
                    "UPLOAD_OBJECT_NOT_FOUND",
                    "尚未发现已上传文件，请确认上传完成后重试",
                    HttpStatus.CONFLICT
            );
        }
    }

    public String sha256(String objectKey) {
        try (
                var stream = internalClient.getObject(
                        GetObjectArgs.builder().bucket(bucket).object(objectKey).build()
                );
                var digestStream = new DigestInputStream(
                        stream, MessageDigest.getInstance("SHA-256")
                )
        ) {
            digestStream.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digestStream.getMessageDigest().digest());
        } catch (Exception exception) {
            throw storageUnavailable(exception);
        }
    }

    public InputStream open(String objectKey) {
        try {
            return internalClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(objectKey).build()
            );
        } catch (Exception exception) {
            throw storageUnavailable(exception);
        }
    }

    public void delete(String objectKey) {
        try {
            internalClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build()
            );
        } catch (Exception exception) {
            throw storageUnavailable(exception);
        }
    }

    private static BusinessException storageUnavailable(Exception cause) {
        return new BusinessException(
                "STORAGE_UNAVAILABLE",
                "私有文件存储暂时不可用",
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    public record StoredObject(long size, String etag, String contentType) {}
}
