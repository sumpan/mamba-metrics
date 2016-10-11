#!/usr/bin/env bash


#JAVA_HOME=/usr/jdk64/jdk1.7.0_45
PIDFILE=/var/run/ambari-metrics-collector/ambari-metrics-collector.pid
OUTFILE=/var/log/ambari-metrics-collector/ambari-metrics-collector.out
STARTUPFILE=/var/log/ambari-metrics-collector/ambari-metrics-collector-startup.out

HBASE_ZK_PID=/var/run/ams-hbase/hbase-${USER}-zookeeper.pid
HBASE_MASTER_PID=/var/run/ams-hbase/hbase-${USER}-master.pid
HBASE_RS_PID=/var/run/ams-hbase/hbase-${USER}-regionserver.pid

HBASE_DIR=/usr/lib/ams-hbase

DAEMON_NAME=timelineserver

COLLECTOR_CONF_DIR=/etc/ambari-metrics-collector/conf
HBASE_CONF_DIR=/etc/ams-hbase/conf

HBASE_CMD=${HBASE_DIR}/bin/hbase

METRIC_TABLES=(METRIC_AGGREGATE_DAILY METRIC_AGGREGATE_HOURLY METRIC_AGGREGATE_MINUTE METRIC_AGGREGATE METRIC_RECORD METRIC_RECORD_DAILY METRIC_RECORD_HOURLY METRIC_RECORD_MINUTE)
METRIC_FIFO_COMPACTION_TABLES=(METRIC_AGGREGATE METRIC_RECORD METRIC_RECORD_MINUTE)
METRIC_COLLECTOR=ambari-metrics-collector

AMS_COLLECTOR_LOG_DIR=/var/log/ambari-metrics-collector

AMS_HBASE_NORMALIZER_ENABLED=true
AMS_HBASE_FIFO_COMPACTION_ENABLED=true
AMS_HBASE_INIT_CHECK_ENABLED=true

NORMALIZER_ENABLED_STUB_FILE=/var/run/ambari-metrics-collector/normalizer_enabled
FIFO_ENABLED_STUB_FILE=/var/run/ambari-metrics-collector/fifo_enabled

STOP_TIMEOUT=5

DISTRIBUTED_HBASE=false

function hbase_daemon
{
    local daemon=$1
    local cmd=$2
    local pid

    case "${daemon}" in
      "master")
        pid=${HBASE_MASTER_PID}
      ;;
      "zookeeper")
        pid=${HBASE_ZK_PID}
      ;;
      "regionserver")
        pid=${HBASE_RS_PID}
      ;;
    esac

    daemon_status "${pid}"
    if [[ $? == 0  ]]; then
        echo "${daemon} is running as process $(cat "${pid}"). Continuing" | tee -a $STARTUPFILE
      else
        # stale pid file, so just remove it and continue on
        rm -f "${pid}" >/dev/null 2>&1
    fi

    ${HBASE_DIR}/bin/hbase-daemon.sh --config ${HBASE_CONF_DIR} ${cmd} ${daemon}

}

function write_pidfile
{
    local pidfile="$1"
    echo $! > "${pidfile}" 2>/dev/null
    if [[ $? -gt 0 ]]; then
      echo "ERROR:  Cannot write pid ${pidfile}." | tee -a $STARTUPFILE
      exit 1;
    fi
}

# TODO replace this with Phoenix DDL, when normalization support added to Phoenix
function enable_normalization_fifo
{
  echo "$(date) Handling HBase normalization/fifo requests" | tee -a $STARTUPFILE
  command=""

  # Enable normalization for all the tables
  echo "$(date) Normalized enabled : ${AMS_HBASE_NORMALIZER_ENABLED}" | tee -a $STARTUPFILE
  if [[ "${AMS_HBASE_NORMALIZER_ENABLED}" == "true" || "${AMS_HBASE_NORMALIZER_ENABLED}" == "True" ]]
  then
    if [ ! -f "$NORMALIZER_ENABLED_STUB_FILE" ] #If stub file not found
    then
      echo "$(date) Normalizer stub file not found" | tee -a $STARTUPFILE
      for table in "${METRIC_TABLES[@]}"
      do
        command="$command \n alter '$table', {NORMALIZATION_ENABLED => 'true'}"
      done
      touch $NORMALIZER_ENABLED_STUB_FILE
    fi
  else
    if [ -f "$NORMALIZER_ENABLED_STUB_FILE" ]  #If stub file found
    then
      echo "$(date) Normalizer stub file found" | tee -a $STARTUPFILE
      rm -f $NORMALIZER_ENABLED_STUB_FILE
    fi
  fi

  #Similarly for HBase FIFO Compaction policy
  echo "$(date) Fifo enabled : ${AMS_HBASE_FIFO_COMPACTION_ENABLED}" | tee -a $STARTUPFILE
  if [[ "${AMS_HBASE_FIFO_COMPACTION_ENABLED}" == "true" || "${AMS_HBASE_FIFO_COMPACTION_ENABLED}" == "True" ]]
  then
    if [ ! -f "$FIFO_ENABLED_STUB_FILE" ] #If stub file not found
    then
      echo "$(date) Fifo stub file not found" | tee -a $STARTUPFILE
      for table in "${METRIC_FIFO_COMPACTION_TABLES[@]}"
      do
        command="$command \n alter '$table', CONFIGURATION => {'hbase.hstore.blockingStoreFiles' => '1000',
        'hbase.hstore.defaultengine.compactionpolicy.class' =>
        'org.apache.hadoop.hbase.regionserver.compactions.FIFOCompactionPolicy'}"
      done
      touch $FIFO_ENABLED_STUB_FILE
    fi
  else
    if [ -f "$FIFO_ENABLED_STUB_FILE" ] #If stub file found
      then
        echo "$(date) Fifo stub file found" | tee -a $STARTUPFILE
        for table in "${METRIC_FIFO_COMPACTION_TABLES[@]}"
        do
          command="$command \n alter '$table', CONFIGURATION => {'hbase.hstore.defaultengine.compactionpolicy.class' =>
          'org.apache.hadoop.hbase.regionserver.compactions.ExploringCompactionPolicy',
          'hbase.hstore.blockingStoreFiles' => '300'}"
        done
        rm -f $FIFO_ENABLED_STUB_FILE
    fi
  fi

  if [[ ! -z "$command" ]]
  then
    echo "$(date) Executing HBase shell command..." | tee -a $STARTUPFILE
    echo -e ${command} | tee -a $STARTUPFILE
    echo -e ${command} | ${HBASE_CMD} --config ${HBASE_CONF_DIR} shell > /dev/null 2>&1
    if [ $? -ne 0 ]; then
      echo "WARNING: Failed to enable Ambari Metrics data model normalization."
      >&2 echo "WARNING: Failed to enable Ambari Metrics data model normalization."
      rm -f $NORMALIZER_ENABLED_STUB_FILE
      rm -f $FIFO_ENABLED_STUB_FILE
    else
      echo "$(date) HBase shell command completed" | tee -a $STARTUPFILE
    fi
  else
    echo "$(date) Nothing to execute against HBase shell" | tee -a $STARTUPFILE
  fi
}

function hadoop_java_setup
{
  # Bail if we did not detect it
  if [[ -z "${JAVA_HOME}" ]]; then
    echo "ERROR: JAVA_HOME is not set and could not be found." | tee -a $STARTUPFILE
    exit 1
  fi

  if [[ ! -d "${JAVA_HOME}" ]]; then
    echo "ERROR: JAVA_HOME ${JAVA_HOME} does not exist." | tee -a $STARTUPFILE
    exit 1
  fi

  JAVA="${JAVA_HOME}/bin/java"

  if [[ ! -x "$JAVA" ]]; then
    echo "ERROR: $JAVA is not executable." | tee -a $STARTUPFILE
    exit 1
  fi
  # shellcheck disable=SC2034
  JAVA_HEAP_MAX=-Xmx1g
  HADOOP_HEAPSIZE=${HADOOP_HEAPSIZE:-1024}

  # check envvars which might override default args
  if [[ -n "$HADOOP_HEAPSIZE" ]]; then
    # shellcheck disable=SC2034
    JAVA_HEAP_MAX="-Xmx${HADOOP_HEAPSIZE}m"
  fi
}

function daemon_status()
{
  #
  # LSB 4.1.0 compatible status command (1)
  #
  # 0 = program is running
  # 1 = dead, but still a pid (2)
  # 2 = (not used by us)
  # 3 = not running
  #
  # 1 - this is not an endorsement of the LSB
  #
  # 2 - technically, the specification says /var/run/pid, so
  #     we should never return this value, but we're giving
  #     them the benefit of a doubt and returning 1 even if
  #     our pid is not in in /var/run .
  #

  local pidfile="$1"
  shift

  local pid

  if [[ -f "${pidfile}" ]]; then
    pid=$(cat "${pidfile}")
    if ps -p "${pid}" > /dev/null 2>&1; then
      return 0
    fi
    return 1
  fi
  return 3
}

function start()
{
  hadoop_java_setup


  # hbase_daemon "zookeeper" "start"
  #	hbase_daemon "master" "start"
  #	hbase_daemon "regionserver" "start"
  if [ !"${DISTRIBUTED_HBASE}" ]; then
    echo "$(date) Starting HBase." | tee -a $STARTUPFILE
    hbase_daemon "master" "start"
  else
    echo "$(date) Launching in distributed mode. Assuming Hbase daemons up and running." | tee -a $STARTUPFILE
  fi

	CLASS='mamba.applicationhistoryservice.ApplicationHistoryServer'
	# YARN_OPTS="${YARN_OPTS} ${YARN_TIMELINESERVER_OPTS}"
	# if [[ -n "${YARN_TIMELINESERVER_HEAPSIZE}" ]]; then
	#   JAVA_HEAP_MAX="-Xmx${YARN_TIMELINESERVER_HEAPSIZE}m"
	# fi

	# check if this is needed?
	# export PHOENIX_JAR_PATH=/usr/lib/ambari-metrics/timelineservice/phoenix-client.jar
	# export HBASE_CONF_DIR=${HBASE_DIR}/conf

  daemon_status "${PIDFILE}"
  if [[ $? == 0  ]]; then
    echo "AMS is running as process $(cat "${PIDFILE}"). Exiting" | tee -a $STARTUPFILE
    exit 0
  else
    # stale pid file, so just remove it and continue on
    rm -f "${PIDFILE}" >/dev/null 2>&1
  fi

  nohup "${JAVA}" "-Xms$AMS_COLLECTOR_HEAPSIZE" "-Xmx$AMS_COLLECTOR_HEAPSIZE" ${AMS_COLLECTOR_OPTS} "-cp" "/usr/lib/ambari-metrics-collector/*:${COLLECTOR_CONF_DIR}" "-Djava.net.preferIPv4Stack=true" "-Dams.log.dir=${AMS_COLLECTOR_LOG_DIR}" "-Dproc_${DAEMON_NAME}" "${CLASS}" "$@" > $OUTFILE 2>&1 &
  PID=$!
  write_pidfile "${PIDFILE}"
  sleep 2

  echo "Verifying ${METRIC_COLLECTOR} process status..." | tee -a $STARTUPFILE
  if [ -z "`ps ax | grep -w ${PID} | grep ApplicationHistoryServer`" ]; then
    if [ -s ${OUTFILE} ]; then
      echo "ERROR: ${METRIC_COLLECTOR} start failed. For more details, see ${OUTFILE}:" | tee -a $STARTUPFILE
      echo "===================="
      tail -n 10 ${OUTFILE}
      echo "===================="
    else
      echo "ERROR: ${METRIC_COLLECTOR} start failed" | tee -a $STARTUPFILE
      rm -f ${PIDFILE}
    fi
    echo "Collector out at: ${OUTFILE}" | tee -a $STARTUPFILE
    exit -1
  fi

  rm -f $STARTUPFILE #Deleting startup file
  echo "$(date) Collector successfully started." | tee -a $STARTUPFILE
  if [[ "${AMS_HBASE_INIT_CHECK_ENABLED}" == "true" || "${AMS_HBASE_INIT_CHECK_ENABLED}" == "True" ]]
  then
    echo "$(date) Initializing Ambari Metrics data model" | tee -a $STARTUPFILE
    start=$SECONDS
    # Wait until METRIC_* tables created
    for retry in {1..5}
    do
      echo 'list' | ${HBASE_CMD} --config ${HBASE_CONF_DIR} shell 2> /dev/null | grep ^${METRIC_TABLES[0]} > /dev/null 2>&1
      if [ $? -eq 0 ]; then
        echo "$(date) Ambari Metrics data model initialization completed." | tee -a $STARTUPFILE
        break
      fi
      echo "$(date) Ambari Metrics data model initialization check $retry" | tee -a $STARTUPFILE
      duration=$(( SECONDS - start ))
      if [ $duration -gt 300 ]; then
        echo "$(date) Ambari Metrics data model initialization timed out" | tee -a $STARTUPFILE
        break
      fi
      sleep 5
    done
    if [ $? -ne 0 ]; then
      echo "WARNING: Ambari Metrics data model initialization failed."
       >&2 echo "WARNING: Ambari Metrics data model initialization failed."
  #  else
  #    enable_normalization_fifo
    fi
  else
    echo "$(date) Skipping Ambari Metrics data model initialization" | tee -a $STARTUPFILE
  fi
  }

function stop()
{
  pidfile=${PIDFILE}

  if [[ -f "${pidfile}" ]]; then
    pid=$(cat "$pidfile")

    kill "${pid}" >/dev/null 2>&1
    sleep "${STOP_TIMEOUT}"

    if kill -0 "${pid}" > /dev/null 2>&1; then
      echo "WARNING: ${METRIC_COLLECTOR} did not stop gracefully after ${STOP_TIMEOUT} seconds: Trying to kill with kill -9" | tee -a $STARTUPFILE
      kill -9 "${pid}" >/dev/null 2>&1
    fi

    if ps -p "${pid}" > /dev/null 2>&1; then
      echo "ERROR: Unable to kill ${pid}" | tee -a $STARTUPFILE
    else
      rm -f "${pidfile}" >/dev/null 2>&1
    fi
  fi

  #stop hbase daemons
  if [ !"${DISTRIBUTED_HBASE}" ]; then
    echo "Stopping HBase master" | tee -a $STARTUPFILE
    hbase_daemon "master" "stop"
  fi
}

while [[ -z "${_ams_configs_done}" ]]; do
  case $1 in
    --config)
      shift
      confdir=$1
      shift
      if [[ -d "${confdir}" ]]; then
        COLLECTOR_CONF_DIR="${confdir}"
      elif [[ -z "${confdir}" ]]; then
        echo "ERROR: No parameter provided for --config " | tee -a $STARTUPFILE
        exit 1
      else
        echo "ERROR: Cannot find configuration directory \"${confdir}\"" | tee -a $STARTUPFILE
        exit 1
      fi
    ;;
    --distributed)
      DISTRIBUTED_HBASE=true
      shift
    ;;
    *)
      _ams_configs_done=true
    ;;
  esac
done

# execute ams-env.sh
if [[ -f "${COLLECTOR_CONF_DIR}/ams-env.sh" ]]; then
  . "${COLLECTOR_CONF_DIR}/ams-env.sh"
else
  echo "ERROR: Cannot execute ${COLLECTOR_CONF_DIR}/ams-env.sh." 2>&1
  exit 1
fi

# set pid dir path
if [[ -n "${AMS_COLLECTOR_PID_DIR}" ]]; then
  PIDFILE=${AMS_COLLECTOR_PID_DIR}/ambari-metrics-collector.pid
  NORMALIZER_ENABLED_STUB_FILE=${AMS_COLLECTOR_PID_DIR}/normalizer_enabled
  FIFO_ENABLED_STUB_FILE=${AMS_COLLECTOR_PID_DIR}/fifo_enabled
fi

if [[ -n "${AMS_HBASE_PID_DIR}" ]]; then
  HBASE_ZK_PID=${AMS_HBASE_PID_DIR}/hbase-${USER}-zookeeper.pid
  HBASE_MASTER_PID=${AMS_HBASE_PID_DIR}/hbase-${USER}-master.pid
  HBASE_RS_PID=${AMS_HBASE_PID_DIR}/hbase-${USER}-regionserver.pid
fi

# set out file path
if [[ -n "${AMS_COLLECTOR_LOG_DIR}" ]]; then
  OUTFILE=${AMS_COLLECTOR_LOG_DIR}/ambari-metrics-collector.out
  STARTUPFILE=${AMS_COLLECTOR_LOG_DIR}/ambari-metrics-collector-startup.out
fi

#TODO manage 3 hbase daemons for start/stop/status
case "$1" in

	start)
    start

    ;;
	stop)
    stop

    ;;
	status)
	    daemon_status "${PIDFILE}"
	    if [[ $? == 0  ]]; then
            echo "AMS is running as process $(cat "${PIDFILE}")."
        else
            echo "AMS is not running."
        fi
        #print embedded hbase daemons statuses?
    ;;
	restart)
	  stop
	  start
	  ;;
  enable_normalization_fifo)
    enable_normalization_fifo
    ;;

esac
