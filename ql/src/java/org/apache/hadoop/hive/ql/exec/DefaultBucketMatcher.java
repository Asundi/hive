/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.fs.Path;

public class DefaultBucketMatcher implements BucketMatcher {

  protected Log LOG = LogFactory.getLog(this.getClass().getName());

  //MAPPING: bucket_file_name_in_big_table->{alias_table->corresonding_bucket_file_names}
  private LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> aliasBucketMapping;

  private LinkedHashMap<String, Integer> bucketFileNameMapping;

  public DefaultBucketMatcher(){
    bucketFileNameMapping = new LinkedHashMap<String, Integer>();
  }

  public List<Path> getAliasBucketFiles(String refTableInputFile, String refTableAlias, String alias) {
    List<String> pathStr=aliasBucketMapping.get(alias).get(refTableInputFile);
    List<Path> paths = new ArrayList<Path>();
    if(pathStr!=null) {
      for (String p : pathStr) {
        LOG.info("Loading file " + p + " for " + alias + ". (" + refTableInputFile + ")");
        paths.add(new Path(p));
      }
    }
    return paths;
  }

  public void setAliasBucketFileNameMapping(
      LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> aliasBucketFileNameMapping) {
    this.aliasBucketMapping = aliasBucketFileNameMapping;
  }

  public LinkedHashMap<String, Integer> getBucketFileNameMapping() {
    return bucketFileNameMapping;
  }

  public void setBucketFileNameMapping(LinkedHashMap<String, Integer> bucketFileNameMapping) {
    this.bucketFileNameMapping = bucketFileNameMapping;
  }

}
