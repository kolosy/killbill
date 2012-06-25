/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.jaxrs.resources;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.ning.billing.ErrorCode;
import com.ning.billing.account.api.Account;
import com.ning.billing.account.api.AccountApiException;
import com.ning.billing.account.api.AccountUserApi;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoiceApiException;
import com.ning.billing.invoice.api.InvoiceNotifier;
import com.ning.billing.invoice.api.InvoiceUserApi;
import com.ning.billing.jaxrs.json.CustomFieldJson;
import com.ning.billing.jaxrs.json.InvoiceJsonSimple;
import com.ning.billing.jaxrs.json.InvoiceJsonWithItems;
import com.ning.billing.jaxrs.json.PaymentJsonSimple;
import com.ning.billing.jaxrs.util.Context;
import com.ning.billing.jaxrs.util.JaxrsUriBuilder;
import com.ning.billing.jaxrs.util.TagHelper;
import com.ning.billing.payment.api.Payment;
import com.ning.billing.payment.api.PaymentApi;
import com.ning.billing.payment.api.PaymentApiException;
import com.ning.billing.util.api.CustomFieldUserApi;
import com.ning.billing.util.api.TagUserApi;
import com.ning.billing.util.dao.ObjectType;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_HTML;


@Path(JaxrsResource.INVOICES_PATH)
public class InvoiceResource extends JaxRsResourceBase {
    private static final String ID_PARAM_NAME = "invoiceId";
    private static final String CUSTOM_FIELD_URI = JaxrsResource.CUSTOM_FIELDS + "/{" + ID_PARAM_NAME + ":" + UUID_PATTERN + "}";
    private static final String TAG_URI = JaxrsResource.TAGS + "/{" + ID_PARAM_NAME + ":" + UUID_PATTERN + "}";

    private final DateTimeFormatter DATE_TIME_FORMATTER = ISODateTimeFormat.dateTime();

    private final AccountUserApi accountApi;
    private final InvoiceUserApi invoiceApi;
    private final PaymentApi paymentApi;
    private final Context context;
    private final JaxrsUriBuilder uriBuilder;
    private final InvoiceNotifier invoiceNotifier;

    @Inject
    public InvoiceResource(final AccountUserApi accountApi,
                           final InvoiceUserApi invoiceApi,
                           final PaymentApi paymentApi,
                           final Context context,
                           final JaxrsUriBuilder uriBuilder,
                           final TagUserApi tagUserApi,
                           final TagHelper tagHelper,
                           final CustomFieldUserApi customFieldUserApi,
                           final InvoiceNotifier invoiceNotifier) {
        super(uriBuilder, tagUserApi, tagHelper, customFieldUserApi);
        this.accountApi = accountApi;
        this.invoiceApi = invoiceApi;
        this.paymentApi = paymentApi;
        this.context = context;
        this.uriBuilder = uriBuilder;
        this.invoiceNotifier = invoiceNotifier;
    }

    @GET
    @Produces(APPLICATION_JSON)
    public Response getInvoices(@QueryParam(QUERY_ACCOUNT_ID) final String accountId) {
        try {
            Preconditions.checkNotNull(accountId, "% query parameter must be specified", QUERY_ACCOUNT_ID);
            accountApi.getAccountById(UUID.fromString(accountId));
            final List<Invoice> invoices = invoiceApi.getInvoicesByAccount(UUID.fromString(accountId));
            final List<InvoiceJsonSimple> result = new LinkedList<InvoiceJsonSimple>();
            for (final Invoice cur : invoices) {
                result.add(new InvoiceJsonSimple(cur));
            }
            return Response.status(Status.OK).entity(result).build();
        } catch (AccountApiException e) {
            return Response.status(Status.NO_CONTENT).build();
        } catch (NullPointerException e) {
            return Response.status(Status.BAD_REQUEST).build();
        }
    }

    @GET
    @Path("/{invoiceId:" + UUID_PATTERN + "}/")
    @Produces(APPLICATION_JSON)
    public Response getInvoice(@PathParam("invoiceId") final String invoiceId, @QueryParam("withItems") @DefaultValue("false") final boolean withItems) {
        final Invoice invoice = invoiceApi.getInvoice(UUID.fromString(invoiceId));
        if (invoice == null) {
            return Response.status(Status.NO_CONTENT).build();
        } else {
            final InvoiceJsonSimple json = withItems ? new InvoiceJsonWithItems(invoice) : new InvoiceJsonSimple(invoice);
            return Response.status(Status.OK).entity(json).build();
        }
    }

    @GET
    @Path("/{invoiceId:" + UUID_PATTERN + "}/html")
    @Produces(TEXT_HTML)
    public Response getInvoiceAsHTML(@PathParam("invoiceId") final String invoiceId) {
        try {
            return Response.status(Status.OK).entity(invoiceApi.getInvoiceAsHTML(UUID.fromString(invoiceId))).build();
        } catch (AccountApiException e) {
            return Response.status(Status.NO_CONTENT).build();
        } catch (IOException e) {
            return Response.status(Status.INTERNAL_SERVER_ERROR).build();
        } catch (InvoiceApiException e) {
            return Response.status(Status.NO_CONTENT).build();
        }
    }

    @POST
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response createFutureInvoice(@QueryParam(QUERY_ACCOUNT_ID) final String accountId,
                                        @QueryParam(QUERY_TARGET_DATE) final String targetDate,
                                        @QueryParam(QUERY_DRY_RUN) @DefaultValue("false") final Boolean dryRun,
                                        @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                        @HeaderParam(HDR_REASON) final String reason,
                                        @HeaderParam(HDR_COMMENT) final String comment) {
        try {
            Preconditions.checkNotNull(accountId, "% needs to be specified", QUERY_ACCOUNT_ID);
            Preconditions.checkNotNull(targetDate, "% needs to be specified", QUERY_TARGET_DATE);

            final DateTime inputDate = (targetDate != null) ? DATE_TIME_FORMATTER.parseDateTime(targetDate) : null;

            accountApi.getAccountById(UUID.fromString(accountId));
            final Invoice generatedInvoice = invoiceApi.triggerInvoiceGeneration(UUID.fromString(accountId), inputDate, dryRun,
                                                                                 context.createContext(createdBy, reason, comment));
            if (dryRun) {
                return Response.status(Status.OK).entity(new InvoiceJsonSimple(generatedInvoice)).build();
            } else {
                return uriBuilder.buildResponse(InvoiceResource.class, "getInvoice", generatedInvoice.getId());
            }
        } catch (AccountApiException e) {
            return Response.status(Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (InvoiceApiException e) {
            if (e.getCode() == ErrorCode.INVOICE_NOTHING_TO_DO.getCode()) {
                return Response.status(Status.NO_CONTENT).build();
            } else {
                return Response.status(Status.BAD_REQUEST).entity(e.getMessage()).build();
            }
        } catch (NullPointerException e) {
            return Response.status(Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/{invoiceId:" + UUID_PATTERN + "}/" + PAYMENTS)
    @Produces(APPLICATION_JSON)
    public Response getPayments(@PathParam("invoiceId") final String invoiceId) {
        try {
            final List<Payment> payments = paymentApi.getInvoicePayments(UUID.fromString(invoiceId));
            final List<PaymentJsonSimple> result = new ArrayList<PaymentJsonSimple>(payments.size());
            for (final Payment cur : payments) {
                result.add(new PaymentJsonSimple(cur));
            }
            return Response.status(Status.OK).entity(result).build();
        } catch (PaymentApiException e) {
            return Response.status(Status.NOT_FOUND).build();
        }
    }

    @POST
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @Path("/{invoiceId:" + UUID_PATTERN + "}/" + PAYMENTS)
    public Response createInstantPayment(final PaymentJsonSimple payment,
                                         @QueryParam(QUERY_PAYMENT_EXTERNAL) @DefaultValue("false") final Boolean externalPayment,
                                         @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                         @HeaderParam(HDR_REASON) final String reason,
                                         @HeaderParam(HDR_COMMENT) final String comment) {
        try {
            final Account account = accountApi.getAccountById(UUID.fromString(payment.getAccountId()));
            paymentApi.createPayment(account, UUID.fromString(payment.getInvoiceId()), null, context.createContext(createdBy, reason, comment));
            return uriBuilder.buildResponse(InvoiceResource.class, "getPayments", payment.getInvoiceId());
        } catch (PaymentApiException e) {
            final String error = String.format("Failed to create payment %s", e.getMessage());
            return Response.status(Status.BAD_REQUEST).entity(error).build();
        } catch (AccountApiException e) {
            final String error = String.format("Failed to create payment, can't find account %s", payment.getAccountId());
            return Response.status(Status.BAD_REQUEST).entity(error).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/{invoiceId:" + UUID_PATTERN + "}/" + EMAIL_NOTIFICATIONS)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response triggerEmailNotificationForInvoice(@PathParam("invoiceId") final String invoiceId,
                                                       @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                                       @HeaderParam(HDR_REASON) final String reason,
                                                       @HeaderParam(HDR_COMMENT) final String comment) {
        final Invoice invoice = invoiceApi.getInvoice(UUID.fromString(invoiceId));
        if (invoice == null) {
            return Response.status(Status.NOT_FOUND).build();
        }

        try {
            final Account account = accountApi.getAccountById(invoice.getAccountId());

            // Send the email (synchronous send)
            invoiceNotifier.notify(account, invoice);

            return Response.status(Status.OK).build();
        } catch (AccountApiException e) {
            return Response.status(Status.NOT_FOUND).build();
        } catch (InvoiceApiException e) {
            // Sending failed
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(e).build();
        }
    }

    @GET
    @Path(CUSTOM_FIELD_URI)
    @Produces(APPLICATION_JSON)
    public Response getCustomFields(@PathParam(ID_PARAM_NAME) final String id) {
        return super.getCustomFields(UUID.fromString(id));
    }

    @POST
    @Path(CUSTOM_FIELD_URI)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response createCustomFields(@PathParam(ID_PARAM_NAME) final String id,
                                       final List<CustomFieldJson> customFields,
                                       @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                       @HeaderParam(HDR_REASON) final String reason,
                                       @HeaderParam(HDR_COMMENT) final String comment) {
        return super.createCustomFields(UUID.fromString(id), customFields,
                                        context.createContext(createdBy, reason, comment));
    }

    @DELETE
    @Path(CUSTOM_FIELD_URI)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response deleteCustomFields(@PathParam(ID_PARAM_NAME) final String id,
                                       @QueryParam(QUERY_CUSTOM_FIELDS) final String customFieldList,
                                       @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                       @HeaderParam(HDR_REASON) final String reason,
                                       @HeaderParam(HDR_COMMENT) final String comment) {
        return super.deleteCustomFields(UUID.fromString(id), customFieldList,
                                        context.createContext(createdBy, reason, comment));
    }

    @GET
    @Path(TAG_URI)
    @Produces(APPLICATION_JSON)
    public Response getTags(@PathParam(ID_PARAM_NAME) final String id) {
        return super.getTags(UUID.fromString(id));
    }

    @POST
    @Path(TAG_URI)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response createTags(@PathParam(ID_PARAM_NAME) final String id,
                               @QueryParam(QUERY_TAGS) final String tagList,
                               @HeaderParam(HDR_CREATED_BY) final String createdBy,
                               @HeaderParam(HDR_REASON) final String reason,
                               @HeaderParam(HDR_COMMENT) final String comment) {
        return super.createTags(UUID.fromString(id), tagList,
                                context.createContext(createdBy, reason, comment));
    }

    @DELETE
    @Path(TAG_URI)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response deleteTags(@PathParam(ID_PARAM_NAME) final String id,
                               @QueryParam(QUERY_TAGS) final String tagList,
                               @HeaderParam(HDR_CREATED_BY) final String createdBy,
                               @HeaderParam(HDR_REASON) final String reason,
                               @HeaderParam(HDR_COMMENT) final String comment) {

        return super.deleteTags(UUID.fromString(id), tagList,
                                context.createContext(createdBy, reason, comment));
    }

    @Override
    protected ObjectType getObjectType() {
        return ObjectType.INVOICE;
    }
}
