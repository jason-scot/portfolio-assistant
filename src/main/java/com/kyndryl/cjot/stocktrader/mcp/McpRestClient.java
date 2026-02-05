package com.kyndryl.cjot.stocktrader.mcp;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * REST client interface for MCP servers
 */
@Path("/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface McpRestClient {

    @GET
    @Path("/health")
    Response health();

    @POST
    @Path("/tools/call")
    Response callTool(Map<String, Object> request);

    @GET
    @Path("/tools/list")
    Response listTools();

    @GET
    @Path("/resources/list")
    Response listResources();

    @POST
    @Path("/resources/read")
    Response readResource(Map<String, Object> request);
}