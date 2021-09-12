/*
 * Copyright 2019 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.spring.billrun.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.billrun.model.Bill;
import io.spring.billrun.model.Usage;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.batch.item.json.JacksonJsonObjectReader;
import org.springframework.batch.item.json.JsonItemReader;
import org.springframework.batch.item.json.builder.JsonItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.task.configuration.EnableTask;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Configuration
@EnableTask
@EnableBatchProcessing
public class BillingConfiguration {
	@Autowired
	public JobBuilderFactory jobBuilderFactory;

	@Autowired
	public StepBuilderFactory stepBuilderFactory;

	@Value("${usage.file.name:classpath:usageinfo.json}")
	private Resource usageResource;

	@Bean
	public Job job1(ItemReader<Usage> reader, ItemProcessor<Usage, Bill> itemProcessor, ItemWriter<Bill> writer, ItemReader<Bill> jdbcItemReader) {
		Step step1 = stepBuilderFactory.get("BillProcessing")
				.<Usage, Bill>chunk(1)
				.reader(reader)
				.processor(itemProcessor)
				.writer(writer)
				.build();

		Step step2 = stepBuilderFactory.get("BillSummary").<Bill, Bill>chunk(1).reader(jdbcItemReader).writer(new ItemWriter<Bill>() {
			@Override
			public void write(List<? extends Bill> list) throws Exception {
				for (Bill bill : list) {
					System.out.println("id=" + bill.getId() + ",firstName=" + bill.getFirstName() + ",lastName=" + bill.getLastName() + ",dataUsage=" + bill.getDataUsage() + ",minutes=" + bill.getMinutes() + ",billAmount=" + bill.getBillAmount());
				}
			}
		}).build();

		return jobBuilderFactory.get("BillJob")
				.incrementer(new RunIdIncrementer())
				.start(step1).next(step2)
				.build();
	}

	@Bean
	@StepScope
	public ItemReader<Bill> jdbcItemReader(DataSource dataSource) {
		JdbcPagingItemReader<Bill> reader = new JdbcPagingItemReader<Bill>();
		reader.setDataSource(dataSource);
		reader.setFetchSize(100);
		reader.setRowMapper(new BillRowMapper());
		MySqlPagingQueryProvider queryProvider = new MySqlPagingQueryProvider();
		queryProvider.setSelectClause("id, first_name, last_name, sum(minutes) as minutes , sum(data_usage) as data_usage,sum(bill_amount) as bill_amount");
		queryProvider.setFromClause("from BILL_STATEMENTS");
		Map<String, Order> map = new HashMap<String, Order>();
		map.put("id", Order.ASCENDING);
		queryProvider.setSortKeys(map);
		queryProvider.setGroupClause("id, first_name, last_name");
		reader.setQueryProvider(queryProvider);

		return reader;
	}

	@Bean
	public ItemWriter<Bill> jdbcBillWriter(DataSource dataSource) {
		JdbcBatchItemWriter<Bill> writer = new JdbcBatchItemWriterBuilder<Bill>()
				.beanMapped()
				.dataSource(dataSource)
				.sql("INSERT INTO BILL_STATEMENTS (id, first_name, last_name, minutes, data_usage,bill_amount) VALUES (:id, :firstName, :lastName, :minutes, :dataUsage, :billAmount)")
				.build();
		return writer;
	}


	@Bean
	public JsonItemReader<Usage> jsonItemReader() {

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonJsonObjectReader<Usage> jsonObjectReader =
				new JacksonJsonObjectReader<>(Usage.class);
		jsonObjectReader.setMapper(objectMapper);

		return new JsonItemReaderBuilder<Usage>()
				.jsonObjectReader(jsonObjectReader)
				.resource(usageResource)
				.name("UsageJsonItemReader")
				.build();
	}

	class BillRowMapper implements RowMapper<Bill> {
		@Override
		public Bill mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new Bill(rs.getLong("id")
					, rs.getString("first_name")
					, rs.getString("last_name")
					, rs.getLong("minutes")
					, rs.getLong("data_usage")
					, rs.getDouble("bill_amount"));
		}

	}

	@Bean
	ItemProcessor<Usage, Bill> billProcessor() {
		return new BillProcessor();
	}

}
