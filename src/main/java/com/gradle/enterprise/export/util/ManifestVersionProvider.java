package com.gradle.enterprise.export.util;

import picocli.CommandLine;

public class ManifestVersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
        return new String[]{
                ManifestVersionProvider.class.getPackage().getImplementationVersion()
        };
    }
}
