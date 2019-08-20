package com.qantasloyalty.lsl.etlservice.etl

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import com.qantasloyalty.lsl.etlservice.mapper.ApplicationMapper
import org.springframework.beans.factory.annotation.Autowired
import java.util.stream.Collectors
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import com.amazonaws.services.dynamodbv2.model.*
import com.qantasloyalty.lsl.etlservice.mapper.ApplicationDataDecryptor
import com.qantasloyalty.lsl.etlservice.model.ApplicationData
import com.qantasloyalty.lsl.etlservice.model.ApplicationDataKey
import com.amazonaws.services.dynamodbv2.model.BatchGetItemRequest
import com.amazonaws.services.dynamodbv2.model.BatchGetItemResult
import com.amazonaws.services.dynamodbv2.model.KeysAndAttributes
import com.amazonaws.services.dynamodbv2.model.AttributeValue


@Component
class Transformer(@Autowired var applicationMapper: ApplicationMapper,
                  @Autowired var dynamoDB: DynamoDB,
                  @Autowired var awsdynamoDB: AmazonDynamoDB,
                  @Autowired var decryptor: ApplicationDataDecryptor) {

    private val APPLICATION_DATA_TABLE_NAME = "avro-dev-integration-motorapplication-application-data"

    val bucketName = "avro-file-transfer"
    val above90AppKeyName = "etl/Car90DayFile-Application.csv"
    val above90PartyKeyName = "etl/Car90DayFile-Party.csv"
    val below90AppKeyName = "etl/Car-Application.csv"
    val below90PartykeyName = "etl/Car-Party.csv"

    private val LOG = LoggerFactory.getLogger(object {}::class.java.`package`.name)
    private val above90AppStream = S3MultipartUploadBufferedOutputStream(bucketName, above90AppKeyName)
    private val above90PartyStream = S3MultipartUploadBufferedOutputStream(bucketName, above90PartyKeyName)
    private val below90AppStream = S3MultipartUploadBufferedOutputStream(bucketName, below90AppKeyName)
    private val below90PartyStream = S3MultipartUploadBufferedOutputStream(bucketName, below90PartykeyName)

    //private val below90AppStream = S3MultipartUploadBufferedOutputStream("below90AppStream","avro-file-transfer","etl/motor-outgoing-c2-below90.csv")

    fun transformScan(scanResult: ScanResult, processorType: TransformType) {
        //Collect all Application table data for items in ScanResult
        val applicationList = scanResult.items.stream()
                .map(applicationMapper::fromAttributeValuesToApplication)
                .collect(Collectors.toList())
        //println("Application List :: $applicationList")
        //Collect all ids
        val applicationDataKeys = applicationList.stream()
                .map { ApplicationDataKey(it.applicationId, it.lastDataCreatedTimestamp) }
                .collect(Collectors.toList())
        val applicationDataList: List<ApplicationData>? = batchGetItems(applicationDataKeys)
        println("BatchGetItems: $applicationDataList")

        when (processorType) {
            TransformType.BELOW_90 -> {
                transformForAppBelow90(applicationDataList)
                transformForPartyBelow90(applicationDataList)
            }

            TransformType.ABOVE_90 -> {
                transformForAppAbove90(applicationDataList)
                transformForPartyAbove90(applicationDataList)
            }
        }
    }

    private fun transformForAppAbove90(applicationDataList: List<ApplicationData>?) {
        pushListToBuffer(applicationDataList, above90AppStream)
    }

    private fun transformForAppBelow90(applicationDataList: List<ApplicationData>?) {
        pushListToBuffer(applicationDataList, below90AppStream)
    }

    private fun transformForPartyAbove90(scanResult: List<ApplicationData>?) {
        pushListToBuffer(scanResult, above90PartyStream)
    }

    private fun transformForPartyBelow90(scanResult: List<ApplicationData>?) {
        pushListToBuffer(scanResult, below90PartyStream)
    }

    private fun pushListToBuffer(batchGetItems: List<ApplicationData>?, above90AppUploadOutputStream: S3MultipartUploadBufferedOutputStream) {
        batchGetItems?.forEach { above90AppUploadOutputStream.write(it.toCsvString().toByteArray()) }
    }

    private fun batchGetItems(applicationKeys: MutableList<ApplicationDataKey>): List<ApplicationData>? {
        val request = BatchGetItemRequest().addRequestItemsEntry(APPLICATION_DATA_TABLE_NAME, KeysAndAttributes())
        applicationKeys.forEach {
            val keysAndAttributes: KeysAndAttributes? = request.requestItems.get(APPLICATION_DATA_TABLE_NAME)
            //keysAndAttributes?.withKeys("applicationId:${it.applicationId}")
            keysAndAttributes?.withKeys(
                    mapOf(
                            "applicationId" to AttributeValue(it.applicationId),
                            "createdTimestamp" to AttributeValue(it.lastDataCreatedTimestamp)
                    )
            )
        }
        val batchGetItemResult: BatchGetItemResult = awsdynamoDB.batchGetItem(request)
        val items: List<MutableMap<String, AttributeValue>>? = batchGetItemResult.responses.get(APPLICATION_DATA_TABLE_NAME)
        println("BatchGetResponse: $items")
        val collect: List<ApplicationData>? = items?.stream()?.map(applicationMapper::fromAttributeValues)?.collect(Collectors.toList())
        return collect
    }

}