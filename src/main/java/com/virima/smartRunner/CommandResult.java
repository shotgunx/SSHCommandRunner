package com.virima.smartRunner;

/**
     * Result class to hold command execution results
     */
    public  class CommandResult {
        private final String output;
        private final String error;
        private final boolean success;
        private final String channelType;
        private final long executionTimeMs;
        
        public CommandResult(String output, String error, boolean success, String channelType, long executionTimeMs) {
            this.output = output;
            this.error = error;
            this.success = success;
            this.channelType = channelType;
            this.executionTimeMs = executionTimeMs;
        }
        
        public String getOutput() { return output; }
        public String getError() { return error; }
        public boolean isSuccess() { return success; }
        public String getChannelType() { return channelType; }
        public long getExecutionTimeMs() { return executionTimeMs; }
        
        @Override
        public String toString() {
            return  this.success?output:error;

        }
    }
    