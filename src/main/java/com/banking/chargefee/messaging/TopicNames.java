package com.banking.chargefee.messaging;

public final class TopicNames {

    private TopicNames() {}

    public static final String FEE_COMMANDS = "fee.commands";
    public static final String FEE_EVENTS = "fee.events";

    /** A message that failed all its retries is parked on "<topic>.DLT". */
    public static final String DLT_SUFFIX = ".DLT";
}