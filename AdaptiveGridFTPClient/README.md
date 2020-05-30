# Custom GridFTP Client for Parameter Auto-tuning

Implementation of parameter tuning algorithms presented below

Dynamic Protocol Tuning Algorithms for High Performance Data Transfers (Europar'13, JPDC'18)  
  https://link.springer.com/chapter/10.1007/978-3-642-40047-6_72
  https://www.sciencedirect.com/science/article/pii/S0743731518303289

HARP: Predictive Transfer Optimization Based on Historical Analysis and Real-Time Probing (SC'16, TPDS'18)  
https://ieeexplore.ieee.org/abstract/document/7877103/
https://ieeexplore.ieee.org/abstract/document/8249824

## Installation

### Requirements
* Linux
* Java 1.8
* Python 2.7 and up
* mvn


## Usage

1. cd into JGlobus folder and run `mvn compile; mvn install` if you face test failures, add `-DskipTests` option to commands
2. cd into AdaptiveGridFTPClient folder and run `mvn compile` 
3. Add a configuration file (config.cfg) in src/main/resources/ and edit as described below (Make sure to enter correct bandwidth and RTT values). See the sample config file in  src/main/resources/sample_config.cfg
4. Run `mvn exec:java` to run the code

## Configuration File
  **-s** $Source_GridFTP_Server  
  **-d** $Destination_GridFTP_Server  
  **-proxy** $Proxy_file_path (Default will try to read from /tmp for running user id)  
  **-cc** $maximum_allowed_concurrency  
  **-rtt** $rtt (round trip time between source and destination) in ms  
  **-bw** $bw (Maximum bandwidth between source and destination) in Gbps  
  **-bs** $buffer_size (TCP buffer size of minimum of source's read and destination's write in MB)  
  **[-single-chunk]** (Will use Single Chunk [SC](http://dl.acm.org/citation.cfm?id=2529904) approach to schedule transfer. Will transfer one chunk at a time)  
  **[-useHysterisis]** (Will use historical data to run modelling and estimate transfer parameters. [HARP]. Requires python to be installed with scipy and sklearn packages)  
  **[-use-dynamic-scheduling]** (Provides dynamic channel reallocation between chunks while transfer is running [ProMC](http://dl.acm.org/citation.cfm?id=2529904))  
  **[-use-online-tuning]** (Provides continous tuning capability to historical data based modeling [HARP](https://ieeexplore.ieee.org/abstract/document/8249824))  
