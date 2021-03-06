/*
 * The MIT License
 *
 *   Copyright (c) 2021, Mahmoud Ben Hassine (mahmoud.benhassine@icloud.com)
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 */
package org.jeasy.batch.jms;

import org.apache.activemq.broker.BrokerService;
import org.apache.commons.io.FileUtils;
import org.jeasy.batch.core.job.Job;
import org.jeasy.batch.core.job.JobBuilder;
import org.jeasy.batch.core.job.JobExecutor;
import org.jeasy.batch.core.job.JobReport;
import org.jeasy.batch.core.processor.RecordCollector;
import org.jeasy.batch.core.reader.StringRecordReader;
import org.jeasy.batch.core.record.Header;
import org.jeasy.batch.core.record.Record;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.jms.*;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jeasy.batch.core.util.Utils.LINE_SEPARATOR;

@SuppressWarnings("unchecked")
public class JmsIntegrationTest {

    public static final String EXPECTED_DATA_SOURCE_NAME = "JMS destination: queue://q";
    public static final String MESSAGE_TEXT = "test";

    private BrokerService broker;

    @Before
    public void setUp() throws Exception {
        broker = new BrokerService();
        broker.addConnector("tcp://localhost:61616");
        broker.start();
    }

    @Test
    public void testJmsRecordReader() throws Exception {
        Context jndiContext = getJndiContext();
        QueueConnectionFactory queueConnectionFactory = (QueueConnectionFactory) jndiContext.lookup("QueueConnectionFactory");
        Queue queue = (Queue) jndiContext.lookup("q");

        QueueConnection queueConnection = queueConnectionFactory.createQueueConnection();
        QueueSession queueSession = queueConnection.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
        QueueSender queueSender = queueSession.createSender(queue);
        queueConnection.start();

        //send a message to the queue
        TextMessage message = queueSession.createTextMessage();
        message.setText(MESSAGE_TEXT);
        queueSender.send(message);

        RecordCollector<Message> recordCollector = new RecordCollector<>();
        Job job = new JobBuilder<Message, Message>()
                .reader(new JmsRecordReader(queueConnectionFactory, queue, 1000))
                .processor(recordCollector)
                .build();

        JobReport jobReport = new JobExecutor().execute(job);

        assertThat(jobReport).isNotNull();
        assertThat(jobReport.getMetrics().getReadCount()).isEqualTo(1);
        assertThat(jobReport.getMetrics().getWriteCount()).isEqualTo(1);

        List<Record<Message>> records = recordCollector.getRecords();

        assertThat(records).isNotNull().isNotEmpty().hasSize(1);

        Record<Message> jmsRecord = records.get(0);
        Header header = jmsRecord.getHeader();
        assertThat(header).isNotNull();
        assertThat(header.getNumber()).isEqualTo(1);
        assertThat(header.getSource()).isEqualTo(EXPECTED_DATA_SOURCE_NAME);

        Message payload = jmsRecord.getPayload();
        assertThat(payload).isInstanceOf(TextMessage.class);

        TextMessage textMessage = (TextMessage) payload;
        assertThat(textMessage.getText()).isEqualTo(MESSAGE_TEXT);

        queueSession.close();
        queueConnection.close();
    }

    @Test
    public void testJmsRecordWriter() throws Exception {
        Context jndiContext = getJndiContext();
        Queue queue = (Queue) jndiContext.lookup("q");
        QueueConnectionFactory queueConnectionFactory = (QueueConnectionFactory) jndiContext.lookup("QueueConnectionFactory");
        QueueConnection queueConnection = queueConnectionFactory.createQueueConnection();
        QueueSession queueSession = queueConnection.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
        queueConnection.start();

        String dataSource = "foo" + LINE_SEPARATOR + "bar";

        Job job = new JobBuilder<String, Message>()
                .reader(new StringRecordReader(dataSource))
                .processor(new JmsMessageTransformer(queueSession))
                .writer(new JmsRecordWriter(queueConnectionFactory, queue))
                .build();

        new JobExecutor().execute(job);

        // Assert that queue contains 2 messages: "foo" and "bar"
        QueueBrowser queueBrowser = queueSession.createBrowser(queue);
        Enumeration enumeration = queueBrowser.getEnumeration();

        assertThat(enumeration.hasMoreElements()).isTrue();
        TextMessage message1 = (TextMessage) enumeration.nextElement();
        assertThat(message1.getText()).isEqualTo("foo");

        assertThat(enumeration.hasMoreElements()).isTrue();
        TextMessage message2 = (TextMessage) enumeration.nextElement();
        assertThat(message2.getText()).isEqualTo("bar");

        assertThat(enumeration.hasMoreElements()).isFalse();

        queueSession.close();
        queueConnection.close();
    }

    @After
    public void tearDown() throws Exception {
        File brokerDataDirectoryFile = broker.getDataDirectoryFile();
        broker.stop();
        FileUtils.deleteDirectory(brokerDataDirectoryFile);
    }

    private Context getJndiContext() throws IOException, NamingException {
        Properties properties = new Properties();
        properties.load(JmsIntegrationTest.class.getResourceAsStream(("/jndi.properties")));
        return new InitialContext(properties);
    }
}
