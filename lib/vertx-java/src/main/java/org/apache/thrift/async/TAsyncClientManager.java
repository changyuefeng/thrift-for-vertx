/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.thrift.async;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolDecorator;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TClientTransport;
import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An async client manager interface which transitions method call objects based
 * on events.
 */
@SuppressWarnings("rawtypes")
public class TAsyncClientManager implements AsyncResponseHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(TAsyncClientManager.class.getName());

  private final TClientTransport transport;
  private final TProtocolFactory inputProtocolFactory;
  private final TProtocolFactory outputProtocolFactory;

  // We have to ensure that different method calls have different seqid's
  private final AtomicInteger seqidGenerator = new AtomicInteger(0);
  
  // It is safe here to use a HashMap since a verticle instance is single-threaded.
  private final Map<Integer, TAsyncMethodCall> seqid2MethodCall = new HashMap<Integer, TAsyncMethodCall>();

  public TAsyncClientManager(TClientTransport transport, TProtocolFactory protocolFactory) {
    this(transport, protocolFactory, protocolFactory);
  }

  public TAsyncClientManager(TClientTransport transport,
      TProtocolFactory inputProtocolFactory,
      TProtocolFactory outputProtocolFactory) {
    if (!transport.isOpen())
      throw new IllegalStateException("transport is not open.");
    this.transport = transport;
    this.inputProtocolFactory = inputProtocolFactory;
    this.outputProtocolFactory = outputProtocolFactory;
    this.transport.setResponseHandler(this);
  }

  public void call(TAsyncMethodCall method) {
    transport.setCurrentMethodCall(method);
    TProtocol oprot = outputProtocolFactory.getProtocol(transport);
    method.start(oprot);
  }

  public int nextSeqId() {
    return seqidGenerator.incrementAndGet();
  }
  
  public void registerMethodCall(int seqid, TAsyncMethodCall methodCall) {
    seqid2MethodCall.put(seqid, methodCall);
  }

  public void unregisterMethodCall(int seqid, TAsyncMethodCall methodCall) {
    TAsyncMethodCall oldMethodCall = seqid2MethodCall.remove(seqid);
    if (oldMethodCall != methodCall) {
      seqid2MethodCall.put(seqid, oldMethodCall);
      throw new IllegalStateException("Failed to unregister method call for seqid: " + seqid);
    }
  }

  @Override
  public void handleResponse(TTransport transport) {
    TProtocol iprot = inputProtocolFactory.getProtocol(transport);
    // Use the actual underlying protocol (e.g. TBinaryProtocol) to read the message header.
    TMessage messageBegin = null;
    try {
      messageBegin = iprot.readMessageBegin();
    } catch (TException e) {
      LOGGER.error("readMessageBegin() failed using the given protocol: " + iprot.getClass().getName(), e);
      return;
    }
    int seqid = messageBegin.seqid;
    TAsyncMethodCall methodCall = seqid2MethodCall.get(seqid);
    if (methodCall == null) {
      LOGGER.warn("Method call for seqid: " + seqid + " does not exist, this may occur because of timeout.");
      return;
    }
    methodCall.responseReady(new StoredMessageProtocol(iprot, messageBegin));
  }

  /**
   * Our goal was to work with any protocol. In order to do that, we needed to
   * allow them to call readMessageBegin() and get a TMessage in exactly the
   * standard format, without the service name prepended to TMessage.name.
   * 
   * Copied from {@link org.apache.thrift.TMultiplexedProcessor}
   */
  private static class StoredMessageProtocol extends TProtocolDecorator {
    TMessage messageBegin;

    public StoredMessageProtocol(TProtocol protocol, TMessage messageBegin) {
      super(protocol);
      this.messageBegin = messageBegin;
    }

    @Override
    public TMessage readMessageBegin() throws TException {
      return messageBegin;
    }
  }

}
