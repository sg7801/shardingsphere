/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.proxy.backend.text.distsql.rdl.impl;

import org.apache.shardingsphere.sharding.distsql.parser.segment.TableRuleSegment;
import org.apache.shardingsphere.sharding.distsql.parser.statement.AlterShardingTableRuleStatement;
import org.apache.shardingsphere.infra.spi.ShardingSphereServiceLoader;
import org.apache.shardingsphere.infra.spi.typed.TypedSPIRegistry;
import org.apache.shardingsphere.infra.yaml.swapper.YamlRuleConfigurationSwapperEngine;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.proxy.backend.exception.DuplicateTablesException;
import org.apache.shardingsphere.proxy.backend.exception.InvalidKeyGeneratorsException;
import org.apache.shardingsphere.proxy.backend.exception.InvalidShardingAlgorithmsException;
import org.apache.shardingsphere.proxy.backend.exception.ResourceNotExistedException;
import org.apache.shardingsphere.proxy.backend.exception.ShardingTableRuleNotExistedException;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingAutoTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.converter.ShardingRuleStatementConverter;
import org.apache.shardingsphere.sharding.spi.KeyGenerateAlgorithm;
import org.apache.shardingsphere.sharding.spi.ShardingAlgorithm;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Alter sharding table rule backend handler.
 */
public final class AlterShardingTableRuleBackendHandler extends RDLBackendHandler<AlterShardingTableRuleStatement> {
    
    static {
        // TODO consider about register once only
        ShardingSphereServiceLoader.register(ShardingAlgorithm.class);
        ShardingSphereServiceLoader.register(KeyGenerateAlgorithm.class);
    }
    
    public AlterShardingTableRuleBackendHandler(final AlterShardingTableRuleStatement sqlStatement, final BackendConnection backendConnection) {
        super(sqlStatement, backendConnection);
    }
    
    @Override
    public void check(final String schemaName, final AlterShardingTableRuleStatement sqlStatement) {
        Collection<String> notExistResources = getNotExistedResources(schemaName, getResources(sqlStatement));
        if (!notExistResources.isEmpty()) {
            throw new ResourceNotExistedException(schemaName, notExistResources);
        }
        Collection<String> duplicateTables = getDuplicateTables(sqlStatement);
        if (!duplicateTables.isEmpty()) {
            throw new DuplicateTablesException(duplicateTables);
        }
        Collection<String> alteredTables = getAlteredTables(sqlStatement);
        if (!findCurrentRuleConfiguration(schemaName, ShardingRuleConfiguration.class).isPresent()) {
            throw new ShardingTableRuleNotExistedException(schemaName, alteredTables);
        }
        Collection<String> existTables = getShardingTables(findCurrentRuleConfiguration(schemaName, ShardingRuleConfiguration.class).get());
        Collection<String> notExistTables = alteredTables.stream().filter(each -> !existTables.contains(each)).collect(Collectors.toList());
        if (!notExistTables.isEmpty()) {
            throw new ShardingTableRuleNotExistedException(schemaName, notExistTables);
        }
        Collection<String> invalidTableAlgorithms = sqlStatement.getRules().stream().map(each -> each.getTableStrategy().getName()).distinct()
                .filter(each -> !TypedSPIRegistry.findRegisteredService(ShardingAlgorithm.class, each, new Properties()).isPresent()).collect(Collectors.toList());
        if (!invalidTableAlgorithms.isEmpty()) {
            throw new InvalidShardingAlgorithmsException(invalidTableAlgorithms);
        }
        Collection<String> invalidKeyGenerators = getKeyGenerators(sqlStatement).stream()
                .filter(each -> !TypedSPIRegistry.findRegisteredService(KeyGenerateAlgorithm.class, each, new Properties()).isPresent()).collect(Collectors.toList());
        if (!invalidKeyGenerators.isEmpty()) {
            throw new InvalidKeyGeneratorsException(invalidKeyGenerators);
        }
    }
    
    @Override
    public void doExecute(final String schemaName, final AlterShardingTableRuleStatement sqlStatement) {
        ShardingRuleConfiguration alteredShardingRuleConfig = new YamlRuleConfigurationSwapperEngine()
                .swapToRuleConfigurations(Collections.singletonList(ShardingRuleStatementConverter.convert(sqlStatement))).stream().map(each -> (ShardingRuleConfiguration) each).findFirst().get();
        ShardingRuleConfiguration shardingRuleConfig = getCurrentRuleConfiguration(schemaName, ShardingRuleConfiguration.class);
        drop(shardingRuleConfig, sqlStatement);
        shardingRuleConfig.getAutoTables().addAll(alteredShardingRuleConfig.getAutoTables());
        shardingRuleConfig.getShardingAlgorithms().putAll(alteredShardingRuleConfig.getShardingAlgorithms());
        shardingRuleConfig.getKeyGenerators().putAll(alteredShardingRuleConfig.getKeyGenerators());
    }
    
    private void drop(final ShardingRuleConfiguration shardingRuleConfig, final AlterShardingTableRuleStatement sqlStatement) {
        getAlteredTables(sqlStatement).forEach(each -> {
            ShardingAutoTableRuleConfiguration shardingAutoTableRuleConfig = shardingRuleConfig.getAutoTables().stream().filter(tableRule -> each.equals(tableRule.getLogicTable())).findAny().get();
            shardingRuleConfig.getAutoTables().remove(shardingAutoTableRuleConfig);
            shardingRuleConfig.getShardingAlgorithms().remove(shardingAutoTableRuleConfig.getShardingStrategy().getShardingAlgorithmName());
            if (Objects.nonNull(shardingAutoTableRuleConfig.getKeyGenerateStrategy())) {
                shardingRuleConfig.getKeyGenerators().remove(shardingAutoTableRuleConfig.getKeyGenerateStrategy().getKeyGeneratorName());
            }
        });
    }
    
    private Collection<String> getDuplicateTables(final AlterShardingTableRuleStatement sqlStatement) {
        return sqlStatement.getRules().stream()
                .collect(Collectors.toMap(TableRuleSegment::getLogicTable, e -> 1, Integer::sum))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Entry::getKey)
                .collect(Collectors.toList());
    }
    
    private Collection<String> getAlteredTables(final AlterShardingTableRuleStatement sqlStatement) {
        return sqlStatement.getRules().stream().map(TableRuleSegment::getLogicTable).collect(Collectors.toList());
    }
    
    private Collection<String> getShardingTables(final ShardingRuleConfiguration shardingRuleConfig) {
        Collection<String> result = new LinkedList<>();
        result.addAll(shardingRuleConfig.getTables().stream().map(ShardingTableRuleConfiguration::getLogicTable).collect(Collectors.toList()));
        result.addAll(shardingRuleConfig.getAutoTables().stream().map(ShardingAutoTableRuleConfiguration::getLogicTable).collect(Collectors.toList()));
        return result;
    }
    
    private Collection<String> getResources(final AlterShardingTableRuleStatement sqlStatement) {
        Collection<String> result = new LinkedHashSet<>();
        sqlStatement.getRules().forEach(each -> result.addAll(each.getDataSources()));
        return result;
    }
    
    private Collection<String> getKeyGenerators(final AlterShardingTableRuleStatement sqlStatement) {
        return sqlStatement.getRules().stream().filter(each -> Objects.nonNull(each.getKeyGenerateStrategy())).map(each -> each.getKeyGenerateStrategy().getName()).collect(Collectors.toSet());
    }
}
