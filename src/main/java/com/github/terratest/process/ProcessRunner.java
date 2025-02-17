package com.github.terratest.process;

import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ProcessRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessRunner.class);

    public static Optional<CommandResponse> runCommand(List<String> commands,
                                                       File directory,
                                                       Consumer<String> stdOutConsumer,
                                                       Consumer<String> stdErrConsumer) {
        LOGGER.info("Send command to runTime: {}", commands);

        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder().command(commands);
            if(directory != null) {
                processBuilder.directory(directory);
            }
            process = processBuilder.start();
            List<String> stdOut = new ArrayList<>();
            List<String> stdErr = new ArrayList<>();
            CompletableFuture<String> soutFut = readOutStream(process.getInputStream());
            CompletableFuture<String> serrFut = readOutStream(process.getErrorStream());
            CompletableFuture<String> resultFut = soutFut.thenCombine(serrFut, (stdout, stderr) -> {
                if (stderr != null && !stderr.isEmpty()) {
                    stdErrConsumer.accept(stderr);
                    stdErr.add(stderr);
                }
                if (stdout != null && !stdout.isEmpty()) {
                    stdOutConsumer.accept(stdout);
                    stdOut.add(stdout);
                }
                return stdout;
            });

            ProcessTimeout timeout = determineTimeoutSettings(commands);
            resultFut.get(timeout.getTimeoutValue(), timeout.getTimeUnit());

            if (process.waitFor(timeout.getTimeoutValue(), timeout.getTimeUnit())) {
                Integer exitValue = process.exitValue();
                final CommandResponse commandResponse
                        = new CommandResponse(stdOut, stdErr, exitValue);
                LOGGER.info("CommandResponse: {}", commandResponse);
                return Optional.of(commandResponse);
            } else {
                LOGGER.error("Command execution timed out.");
                return Optional.empty();
            }

        } catch (Throwable exception) {
            LOGGER.error("Error happened executing the commands: ", exception);
            return Optional.empty();
        }
    }

    private static ProcessTimeout determineTimeoutSettings(List<String> commands) {
    	List<String> timeout = commands
                .stream()
                .filter(x -> x.contains("timeout"))
                .collect(Collectors.toList());
    	
    	if (CollectionUtils.isEmpty(timeout)) {
    		LOGGER.info("No timeout override provided so using default of 10 minutes");
    		return new ProcessTimeout(10, TimeUnit.MINUTES);
    	}
    	
    	String timeoutWithUnit = timeout.get(0).split("=")[1];
    	String timeoutValue = timeoutWithUnit.substring(0, timeoutWithUnit.length()-1);
    	String timeoutUnit = timeoutWithUnit.substring(timeoutWithUnit.length()-1);
    	
    	if (timeoutUnit.equals("m")) {
    		LOGGER.info(String.format("Timeout override set to %s minutes", timeoutValue));
    		return new ProcessTimeout(Long.parseLong(timeoutValue), TimeUnit.MINUTES);
    	} else if (timeoutUnit.equals("h")) {
    		LOGGER.info(String.format("Timeout override set to %s hours", timeoutValue));
    		return new ProcessTimeout(Long.parseLong(timeoutValue), TimeUnit.HOURS);
    	} else {
    		throw new RuntimeException(String.format("Unsupported timeout unit: %s", timeoutUnit));
    	}
	}

	static CompletableFuture<String> readOutStream(InputStream is) {
        return CompletableFuture.supplyAsync(() -> {
            try (
                    InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
                    BufferedReader br = new BufferedReader(isr)
            ) {
                StringBuilder res = new StringBuilder();
                String inputLine;
                while ((inputLine = br.readLine()) != null) {
                    res.append(inputLine).append(System.lineSeparator());
                }
                return res.toString();
            } catch (Throwable e) {
                throw new RuntimeException("problem with executing program", e);
            }
        });
    }
}
