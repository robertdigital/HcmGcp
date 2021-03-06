package com.extremenetworks.hcm.gcp.metrics;

import java.io.ByteArrayOutputStream;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.extremenetworks.hcm.gcp.utils.WebResponse;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Path("metrics")
public class MetricsRes {

	private static final Logger logger = LogManager.getLogger(MetricsRes.class);
	private static ObjectMapper jsonMapper = new ObjectMapper();
	private static final JsonFactory jsonFactory = new JsonFactory();

	private final String rabbitServer = "hcm-rabbit-mq";
	private final static String RABBIT_QUEUE_NAME = "gcp.resources";
	private static Channel rabbitChannel;

	private final String dbConnString = "jdbc:mysql://hcm-mysql:3306/Resources?useSSL=false";
	private final String dbUser = "root";
	private final String dbPassword = "password";

	private ExecutorService executor;

	private final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	public MetricsRes() {

		try {
			executor = Executors.newCachedThreadPool();

			ConnectionFactory factory = new ConnectionFactory();
			factory.setHost(rabbitServer);

			Connection connection = factory.newConnection();
			rabbitChannel = connection.createChannel();
			rabbitChannel.queueDeclare(RABBIT_QUEUE_NAME, true, false, false, null);

		} catch (Exception ex) {
			logger.error("Error setting up the 'Resources' resource", ex);
		}
	}

	/**
	 * Retrieves all resources (VMs, subnets, networks, etc.) for the given project
	 * ID from the DB
	 */
	@GET
	@Path("all")
	public String retrieveAllmetrics(@QueryParam("projectId") String projectId) {

		String dbmetricsData = retrieveDataFromDb(projectId);

		return dbmetricsData;
	}

	/**
	 * Starts a background worker that pulls all resources from the given account.
	 * This is a non-blocking REST call that just starts that worker in a separate
	 * thread and immediately responds to the caller. Once the background worker is
	 * done retrieving all data from AWS it will - update the DB - publish the data
	 * to RabbitMQ
	 * 
	 * @param accountId
	 * @param accessKeyId
	 * @param accessKeySecret
	 * @return
	 */
	@POST
	@Path("triggerUpdate")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public String triggerUpdateAll(String authFileContent, @QueryParam("projectId") String projectId) {

		try {
			if (projectId == null || projectId.isEmpty()) {

				String msg = "The projectId query parameter is not provided - not triggering an update!";
				logger.warn(msg);
				return jsonMapper.writeValueAsString(new WebResponse(1, msg));
			}

			/* Config and start the background worker */
			logger.debug("Creating background worker to import metrics data from GPC project " + projectId);

			executor.execute(new MetricsWorker(projectId, authFileContent, RABBIT_QUEUE_NAME, rabbitChannel));

			return jsonMapper
					.writeValueAsString(new WebResponse(0, "Successfully triggered an update of all metrics data"));

		} catch (Exception ex) {
			logger.error(
					"Error parsing parameters and trying to setup the background worker to trigger an update on all metrics data",
					ex);
			return "";
		}
	}

	/**
	 * Retrieves all resource data for the given account from the DB. Generate a
	 * JSON-formated string. Example: { "dataType": "resources", "sourceSystemType":
	 * "gcp", "sourceSystemProjectId": "418454969983", "data": [ { "lastUpdated":
	 * "2019-04-05 15:22:38", "resourceType": "Subnet", "resourceData": [ { "tags":
	 * [], "state": "available", "vpcId": "vpc-d3358ab6", ... }, ...
	 * 
	 * @param projectId
	 * @return
	 */
	private String retrieveDataFromDb(String projectId) {

		logger.debug("Retrieving all resource data for GCP project " + projectId + " from the DB");

		try {
			java.sql.Connection con = DriverManager.getConnection(dbConnString, dbUser, dbPassword);

			// Query the DB for all resource data for the given account ID
			String query = "SELECT lastUpdated, resourceType, resourceData FROM gcp WHERE projectId = '" + projectId
					+ "'";

			Statement st = con.createStatement();
			ResultSet rs = st.executeQuery(query);

			/*
			 * Start building the JSON string which contains some meta data. Example:
			 * 
			 * "dataType": "resources", "sourceSystemType": "gcp", "sourceSystemProjectId":
			 * "418454969983",
			 */
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			JsonGenerator jsonGen = jsonFactory.createGenerator(outputStream, JsonEncoding.UTF8);

			jsonGen.writeStartObject();

			jsonGen.writeStringField("dataType", "resources");
			jsonGen.writeStringField("sourceSystemType", "gcp");
			jsonGen.writeStringField("sourceSystemProjectId", projectId);

			/*
			 * The "data" field will contain an array of objects. Each object will contain
			 * all data on a particular resource type
			 */
			jsonGen.writeArrayFieldStart("data");

			while (rs.next()) {

				String resourceType = rs.getString("resourceType");
				logger.debug("Retrieved next DB row: " + ", lastUpdated: " + rs.getString("lastUpdated")
						+ ", resourceType: " + rs.getString("resourceType") + ", resourceData: "
						+ rs.getString("resourceData").substring(0, 200) + "...");

				if (resourceType != null && !resourceType.isEmpty()) {

					jsonGen.writeStartObject();

					/*
					 * Per resource type, the following meta data will be written (example):
					 * "lastUpdated": "2019-04-05 15:22:38", "resourceType": "Subnet",
					 * "resourceData": [ ... list of subnets ... ]
					 */
					jsonGen.writeStringField("lastUpdated", dateFormatter.format(rs.getTimestamp("lastUpdated")));
					jsonGen.writeStringField("resourceType", rs.getString("resourceType"));

					// The list of subnets is already stored as a JSON string in the DB
					jsonGen.writeFieldName("resourceData");
					jsonGen.writeRawValue(rs.getString("resourceData"));

					jsonGen.writeEndObject();
				}

			}

			// Finalize the JSON string and output stream
			jsonGen.writeEndArray();
			jsonGen.writeEndObject();

			jsonGen.close();
			outputStream.close();

			return outputStream.toString();

		} catch (Exception ex) {
			logger.error(ex);
		}

		return "";
	}

}