package com.github.mgk.gradle

import com.amazonaws.auth.AWSCredentialsProviderChain
import com.amazonaws.auth.EC2ContainerCredentialsProviderWrapper
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
import com.amazonaws.auth.SystemPropertiesCredentialsProvider
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.event.ProgressListener
import com.amazonaws.event.ProgressEvent
import com.amazonaws.services.s3.transfer.Transfer
import com.amazonaws.services.s3.transfer.TransferManager
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Plugin
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.text.DecimalFormat
import java.nio.file.Path


class S3Extension {
    String profile
    String region
    String bucket
}


abstract class S3Task extends DefaultTask {
    @Input
    String bucket

    String getBucket() { bucket ?: project.s3.bucket }

    @Internal
    def getS3Client() {
        def profileCreds
        if (project.s3.profile) {
            logger.quiet("Using AWS credentials profile: ${project.s3.profile}")
            profileCreds = new ProfileCredentialsProvider(project.s3.profile)
        }
        else {
            profileCreds = new ProfileCredentialsProvider()
        }
        def creds = new AWSCredentialsProviderChain(
                new EnvironmentVariableCredentialsProvider(),
                new SystemPropertiesCredentialsProvider(),
                profileCreds,
                new EC2ContainerCredentialsProviderWrapper()
        )

        AmazonS3Client s3Client = new AmazonS3Client(creds)
        String region = project.s3.region
        if (region) {
            s3Client.region = Region.getRegion(Regions.fromName(region))
        }
        s3Client
    }
}


class S3Upload extends S3Task {
    @Input
    String key

    @Input
    String file

    @Input
    boolean getOverwrite() {
        _overwrite
    }
    boolean setOverwrite(boolean value) {
        _overwrite = value
    }
    private boolean _overwrite

    @TaskAction
    def task() {

        // validation
        if (!bucket) {
            throw new GradleException('Invalid parameters: [bucket] was not provided and/or a default was not set')
        }

        if (s3Client.doesObjectExist(bucket, key)) {
            if (overwrite) {
                logger.quiet("S3 Upload ${file} → s3://${bucket}/${key} with overwrite")
                s3Client.putObject(bucket, key, new File(file))
            }
            else {
                logger.quiet("s3://${bucket}/${key} exists, not overwriting")
            }
        }
        else {
            logger.quiet("S3 Upload ${file} → s3://${bucket}/${key}")
            s3Client.putObject(bucket, key, new File(file))
        }
    }
}


class S3Download extends S3Task {
    @Optional
    @Input
    String key

    @Optional
    @Input
    String file

    @Optional
    @Input
    String keyPrefix

    @Optional
    @Input
    String destDir

    @TaskAction
    def task() {
        TransferManager tm = new TransferManager(getS3Client())
        Transfer transfer

        // validate bucket
        if (!bucket) {
            throw new GradleException('Invalid parameters: [bucket] was not provided and/or a default was not set')
        }

        // directory download
        if (keyPrefix && destDir) {
            if (key || file) {
                throw new GradleException('Invalid parameters: [key, file] are not valid for S3 Download recursive')
            }
            logger.quiet("S3 Download recursive s3://${bucket}/${keyPrefix} → ${project.file(destDir)}/")
            transfer = tm.downloadDirectory(bucket, keyPrefix, project.file(destDir))
        }

        // single file download
        else if (key && file) {
            if (keyPrefix || destDir) {
                throw new GradleException('Invalid parameters: [keyPrefix, destDir] are not valid for S3 Download single file')
            }
            logger.quiet("S3 Download s3://${bucket}/${key} → ${file}")
            File f = new File(file)
            f.parentFile.mkdirs()
            transfer = tm.download(bucket, key, f)
        }

        // invalid params
        else {
            throw new GradleException('Invalid parameters: one of [key, file] or [keyPrefix, destDir] pairs must be specified for S3 Download')
        }

        def listener = new S3Listener()
        listener.transfer = transfer
        transfer.addProgressListener(listener)
        transfer.waitForCompletion()
    }

    class S3Listener implements ProgressListener {
        Transfer transfer

        DecimalFormat df = new DecimalFormat("#0.0")
        public void progressChanged(ProgressEvent e) {
            logger.info("${df.format(transfer.progress.percentTransferred)}%")
        }
    }
}


class S3Plugin implements Plugin<Project> {
    void apply(Project target) {
        target.extensions.create('s3', S3Extension)
    }
}
