"""Writes Calque.xcodeproj/project.pbxproj from the files in Calque/."""
import hashlib, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def gid(s): return hashlib.md5(s.encode()).hexdigest()[:24].upper()

swift = sorted(f for f in os.listdir(os.path.join(ROOT, "Calque")) if f.endswith(".swift"))
cfiles = sorted(f for f in os.listdir(os.path.join(ROOT, "Calque")) if f.endswith(".c"))
headers = sorted(f for f in os.listdir(os.path.join(ROOT, "Calque")) if f.endswith(".h"))
resources = [("Assets.xcassets", "folder.assetcatalog"), ("licenses.txt", "text")]
XCF = "PDFium/pdfium.xcframework"

P=gid("project"); T=gid("target"); G_MAIN=gid("main group"); G_APP=gid("app group"); G_PROD=gid("products")
APP=gid("product app"); SRC=gid("sources phase"); RES=gid("resources phase"); FW=gid("frameworks phase")
EMB=gid("embed phase"); INFO_F=gid("file info")
XCF_F=gid("file xcf"); XCF_B=gid("build xcf"); XCF_E=gid("embed xcf")
CL_P=gid("cl project"); CL_T=gid("cl target")
DBG_P=gid("debug p"); REL_P=gid("release p"); DBG_T=gid("debug t"); REL_T=gid("release t")

ftype = {".swift": "sourcecode.swift", ".c": "sourcecode.c.c", ".h": "sourcecode.c.h"}
fileref = ""
for f in swift + cfiles + headers:
    fileref += f'\t\t{gid("f"+f)} /* {f} */ = {{isa = PBXFileReference; lastKnownFileType = {ftype[os.path.splitext(f)[1]]}; path = "{f}"; sourceTree = "<group>"; }};\n'
for f, t in resources:
    fileref += f'\t\t{gid("f"+f)} /* {f} */ = {{isa = PBXFileReference; lastKnownFileType = {t}; path = "{f}"; sourceTree = "<group>"; }};\n'
fileref += f'\t\t{XCF_F} /* pdfium.xcframework */ = {{isa = PBXFileReference; lastKnownFileType = wrapper.xcframework; path = "{XCF}"; sourceTree = "<group>"; }};\n'

build = ""
for f in swift + cfiles:
    build += f'\t\t{gid("b"+f)} /* {f} in Sources */ = {{isa = PBXBuildFile; fileRef = {gid("f"+f)} /* {f} */; }};\n'
for f, _ in resources:
    build += f'\t\t{gid("b"+f)} /* {f} in Resources */ = {{isa = PBXBuildFile; fileRef = {gid("f"+f)} /* {f} */; }};\n'
build += f'\t\t{XCF_B} /* pdfium.xcframework in Frameworks */ = {{isa = PBXBuildFile; fileRef = {XCF_F} /* pdfium.xcframework */; }};\n'
build += f'\t\t{XCF_E} /* pdfium.xcframework in Embed Frameworks */ = {{isa = PBXBuildFile; fileRef = {XCF_F} /* pdfium.xcframework */; settings = {{ATTRIBUTES = (CodeSignOnCopy, RemoveHeadersOnCopy, ); }}; }};\n'

children = "".join(f'\t\t\t\t{gid("f"+f)} /* {f} */,\n' for f in swift + cfiles + headers + [r for r, _ in resources])
srcs = "".join(f'\t\t\t\t{gid("b"+f)} /* {f} in Sources */,\n' for f in swift + cfiles)
ress = "".join(f'\t\t\t\t{gid("b"+f)} /* {f} in Resources */,\n' for f, _ in resources)

common_t = '''				ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
				ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor;
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 2;
				DEVELOPMENT_TEAM = "";
				HEADER_SEARCH_PATHS = "$(PROJECT_DIR)/Calque/PDFium/include";
				INFOPLIST_FILE = Calque/Info.plist;
				IPHONEOS_DEPLOYMENT_TARGET = 17.0;
				LD_RUNPATH_SEARCH_PATHS = (
					"$(inherited)",
					"@executable_path/Frameworks",
				);
				MARKETING_VERSION = 1.1;
				PRODUCT_BUNDLE_IDENTIFIER = ge.calque.viewer;
				PRODUCT_NAME = Calque;
				SDKROOT = iphoneos;
				SUPPORTS_MACCATALYST = NO;
				SWIFT_EMIT_LOC_STRINGS = NO;
				SWIFT_OBJC_BRIDGING_HEADER = "Calque/Calque-Bridging-Header.h";
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = "1,2";
'''
common_p = '''				ALWAYS_SEARCH_USER_PATHS = NO;
				CLANG_ENABLE_MODULES = YES;
				CLANG_ENABLE_OBJC_ARC = YES;
				ENABLE_STRICT_OBJC_MSGSEND = YES;
				GCC_C_LANGUAGE_STANDARD = gnu11;
				GCC_NO_COMMON_BLOCKS = YES;
				IPHONEOS_DEPLOYMENT_TARGET = 17.0;
				SDKROOT = iphoneos;
'''
pbx = f'''// !$*UTF8*$!
{{
	archiveVersion = 1;
	classes = {{
	}};
	objectVersion = 56;
	objects = {{

/* Begin PBXBuildFile section */
{build}/* End PBXBuildFile section */

/* Begin PBXCopyFilesBuildPhase section */
		{EMB} /* Embed Frameworks */ = {{
			isa = PBXCopyFilesBuildPhase;
			buildActionMask = 2147483647;
			dstPath = "";
			dstSubfolderSpec = 10;
			files = (
				{XCF_E} /* pdfium.xcframework in Embed Frameworks */,
			);
			name = "Embed Frameworks";
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXCopyFilesBuildPhase section */

/* Begin PBXFileReference section */
		{APP} /* Calque.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = Calque.app; sourceTree = BUILT_PRODUCTS_DIR; }};
{fileref}		{INFO_F} /* Info.plist */ = {{isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = "<group>"; }};
/* End PBXFileReference section */

/* Begin PBXFrameworksBuildPhase section */
		{FW} /* Frameworks */ = {{
			isa = PBXFrameworksBuildPhase;
			buildActionMask = 2147483647;
			files = (
				{XCF_B} /* pdfium.xcframework in Frameworks */,
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXFrameworksBuildPhase section */

/* Begin PBXGroup section */
		{G_MAIN} = {{
			isa = PBXGroup;
			children = (
				{G_APP} /* Calque */,
				{G_PROD} /* Products */,
			);
			sourceTree = "<group>";
		}};
		{G_APP} /* Calque */ = {{
			isa = PBXGroup;
			children = (
{children}				{XCF_F} /* pdfium.xcframework */,
				{INFO_F} /* Info.plist */,
			);
			path = Calque;
			sourceTree = "<group>";
		}};
		{G_PROD} /* Products */ = {{
			isa = PBXGroup;
			children = (
				{APP} /* Calque.app */,
			);
			name = Products;
			sourceTree = "<group>";
		}};
/* End PBXGroup section */

/* Begin PBXNativeTarget section */
		{T} /* Calque */ = {{
			isa = PBXNativeTarget;
			buildConfigurationList = {CL_T} /* Build configuration list for PBXNativeTarget "Calque" */;
			buildPhases = (
				{SRC} /* Sources */,
				{FW} /* Frameworks */,
				{RES} /* Resources */,
				{EMB} /* Embed Frameworks */,
			);
			buildRules = (
			);
			dependencies = (
			);
			name = Calque;
			productName = Calque;
			productReference = {APP} /* Calque.app */;
			productType = "com.apple.product-type.application";
		}};
/* End PBXNativeTarget section */

/* Begin PBXProject section */
		{P} /* Project object */ = {{
			isa = PBXProject;
			attributes = {{
				BuildIndependentTargetsInParallel = 1;
				LastSwiftUpdateCheck = 1500;
				LastUpgradeCheck = 1500;
				TargetAttributes = {{
					{T} = {{
						CreatedOnToolsVersion = 15.0;
					}};
				}};
			}};
			buildConfigurationList = {CL_P} /* Build configuration list for PBXProject "Calque" */;
			compatibilityVersion = "Xcode 14.0";
			developmentRegion = en;
			hasScannedForEncodings = 0;
			knownRegions = (
				en,
				Base,
			);
			mainGroup = {G_MAIN};
			productRefGroup = {G_PROD} /* Products */;
			projectDirPath = "";
			projectRoot = "";
			targets = (
				{T} /* Calque */,
			);
		}};
/* End PBXProject section */

/* Begin PBXResourcesBuildPhase section */
		{RES} /* Resources */ = {{
			isa = PBXResourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
{ress}			);
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXResourcesBuildPhase section */

/* Begin PBXSourcesBuildPhase section */
		{SRC} /* Sources */ = {{
			isa = PBXSourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
{srcs}			);
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXSourcesBuildPhase section */

/* Begin XCBuildConfiguration section */
		{DBG_P} /* Debug */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{common_p}				DEBUG_INFORMATION_FORMAT = dwarf;
				ENABLE_TESTABILITY = YES;
				GCC_OPTIMIZATION_LEVEL = 0;
				ONLY_ACTIVE_ARCH = YES;
				SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;
				SWIFT_OPTIMIZATION_LEVEL = "-Onone";
			}};
			name = Debug;
		}};
		{REL_P} /* Release */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{common_p}				DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym";
				SWIFT_COMPILATION_MODE = wholemodule;
				SWIFT_OPTIMIZATION_LEVEL = "-O";
				VALIDATE_PRODUCT = YES;
			}};
			name = Release;
		}};
		{DBG_T} /* Debug */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{common_t}			}};
			name = Debug;
		}};
		{REL_T} /* Release */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{common_t}			}};
			name = Release;
		}};
/* End XCBuildConfiguration section */

/* Begin XCConfigurationList section */
		{CL_P} /* Build configuration list for PBXProject "Calque" */ = {{
			isa = XCConfigurationList;
			buildConfigurations = (
				{DBG_P} /* Debug */,
				{REL_P} /* Release */,
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		}};
		{CL_T} /* Build configuration list for PBXNativeTarget "Calque" */ = {{
			isa = XCConfigurationList;
			buildConfigurations = (
				{DBG_T} /* Debug */,
				{REL_T} /* Release */,
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		}};
/* End XCConfigurationList section */
	}};
	rootObject = {P} /* Project object */;
}}
'''
open(os.path.join(ROOT, "Calque.xcodeproj", "project.pbxproj"), "w").write(pbx)
print("files:", len(swift), "swift,", len(cfiles), "c,", len(headers), "h")
