package org.keysupport.api.controller.vss;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.jose4j.base64url.internal.apache.commons.codec.binary.Base64;
import org.keysupport.api.config.ConfigurationPolicies;
import org.keysupport.api.controller.ServiceException;
import org.keysupport.api.pkix.ValidatePKIX;
import org.keysupport.api.pkix.X509Util;
import org.keysupport.api.pojo.vss.ValidationPolicy;
import org.keysupport.api.pojo.vss.VssRequest;
import org.keysupport.api.pojo.vss.VssResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
public class ValidateController {

	private final static Logger LOG = LoggerFactory.getLogger(ValidateController.class);

	/**
	 * Field OID_SIZE_LIMIT
	 */
	private final int OID_SIZE_LIMIT = 50;

	/**
	 * Field PEM_SIZE_LIMIT
	 */
	private final int PEM_SIZE_LIMIT = 8192;

	@PostMapping(path = "/vss/v2/validate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<VssResponse> validate(@RequestBody VssRequest request, @RequestHeader Map<String, String> headers) {

		ObjectMapper mapper = new ObjectMapper();
		ASN1ObjectIdentifier oid = null;
		X509Certificate clientCert = null;
		CertificateFactory cf = null;
		ByteArrayInputStream bais = null;
		;
		String x5tS256 = null;

		/*
		 * First, lets validate the request.
		 * 
		 * TODO: Any errors should be logged, along with all client headers, via
		 *   - LOG.error(...)
		 */

		/*
		 * Ensure we have validationPolicy and clientCertificate
		 */
		if (null == request || null == request.validationPolicy || null == request.x509Certificate
				|| null == request.wantBackList) {
			throw new ServiceException("Request must include validationPolicy, wantBackList, and x509CertificateList");
		}

		/*
		 * Check the validationPolicy
		 */
		if (request.validationPolicy.length() >= OID_SIZE_LIMIT) {
			throw new ServiceException("Size limit for validationPolicy Object Identifier exceeded");
		} else {
			try {
				oid = new ASN1ObjectIdentifier(request.validationPolicy);
			} catch (IllegalArgumentException e) {
				throw new ServiceException("validationPolicy must be an Object Identifier");
			}
		}
		
		/*
		 * Check to see if we have the policy, otherwise throw an error
		 */
		ValidationPolicy valPol = ConfigurationPolicies.getPolicy(oid.toString());
		if (null == valPol) {
			LOG.error("Invalid Policy Specified: " + oid.toString());
			throw new ServiceException("Invalid Policy Specified");
		}

		/*
		 * Check the x509Certificate
		 */
		String pemCert = request.x509Certificate;
		try {
			if (pemCert.length() >= PEM_SIZE_LIMIT) {
				throw new ServiceException("Size limit for x509Certificate exceeded");
			}
			byte[] certBytes = null;
			try {
				certBytes = Base64.decodeBase64(pemCert);
			} catch (Throwable e) {
				LOG.error("Error decoding certificate, returning SERVICEFAIL", e);
				throw new ServiceException("Error decoding x509Certificate");
			}
			if (null != certBytes) {
				cf = CertificateFactory.getInstance("X509");
				bais = new ByteArrayInputStream(certBytes);
				clientCert = (X509Certificate) cf.generateCertificate(bais);
			} else {
				LOG.error("Error decoding certificate base64 (null result), returning SERVICEFAIL");
				throw new ServiceException("Error decoding x509Certificate");
			}
		} catch (CertificateException e) {
			LOG.error("Error decoding certificate, returning SERVICEFAIL", e);
			throw new ServiceException("Error decoding x509Certificate");
		}

		/*
		 * Derive x5t#S256
		 */
		x5tS256 = X509Util.x5tS256(clientCert);

		/*
		 * Add metadata to the request via `additionalProperties` so we can log it.
		 */
		request.setAdditionalProperty("x5t#S256", x5tS256);
		request.setAdditionalProperty("requestHeaders", headers);

		/*
		 * Log the request in JSON
		 */
		try {
			String output = mapper.writeValueAsString(request);
			LOG.info("{\"ValidationRequest\":" + output + "}");
		} catch (JsonGenerationException e) {
			LOG.error("Error converting POJO to JSON", e);
		} catch (JsonMappingException e) {
			LOG.error("Error converting POJO to JSON", e);
		} catch (IOException e) {
			LOG.error("Error converting POJO to JSON", e);
		}

		/*
		 * Validate, log, and; return the result
		 */
		VssResponse response = ValidatePKIX.validate(clientCert, x5tS256, valPol, request.wantBackList);
		try {
			String output = mapper.writeValueAsString(response);
			LOG.info("{\"ValidationResponse\":" + output + "}");
		} catch (JsonGenerationException e) {
			LOG.error("Error converting POJO to JSON", e);
		} catch (JsonMappingException e) {
			LOG.error("Error converting POJO to JSON", e);
		} catch (IOException e) {
			LOG.error("Error converting POJO to JSON", e);
		}
		return new ResponseEntity<>(response, HttpStatus.OK);

	}

}
