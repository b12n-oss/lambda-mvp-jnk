(ns script.aws-lifecycle
  "Generic (no hardcoded profile/account/region) create-or-update / invoke /
  teardown for the lambda-mvp-jnk demo function, driven entirely by
  whatever the caller's aws CLI already has configured (AWS_PROFILE/
  AWS_REGION env vars, or `aws configure`). Deploys a Lambda CONTAINER
  IMAGE (package-type Image), not a zip -- see docs/guide/
  container-image-build.md for why. Invoked via `bb deploy`/`bb invoke`/
  `bb teardown`, or directly:
  `bb script/aws_lifecycle.clj deploy|invoke|teardown` (jank itself
  can't run this file: it needs cheshire.core, which jank doesn't
  bundle, same reason the Jolt version's copy of this file needs
  babashka too)."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def function-name (or (System/getenv "LAMBDA_MVP_FUNCTION_NAME") "lambda-mvp-jnk"))
(def role-name (str function-name "-role"))
(def repo-name function-name)
(def local-image-tag (str function-name ":latest"))
(def policy-arn "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")

(defn- sh [& args]
  (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true} args)]
    {:exit exit :out out :err err}))

(defn- die! [& msg]
  (binding [*out* *err*]
    (apply println "lambda-mvp-jnk:" msg))
  (System/exit 1))

(defn- require-aws-identity!
  "Fail fast with a clear message if the aws CLI has no usable
  credentials/region, rather than letting a later call fail obscurely.
  Returns {:account :region} for callers that need them (the ECR URI
  construction does)."
  []
  (let [{:keys [exit out err]} (sh "aws" "sts" "get-caller-identity" "--output" "json")]
    (when-not (zero? exit)
      (die! "aws CLI has no usable credentials/region."
            "Set AWS_PROFILE/AWS_REGION or run `aws configure`, then retry.\n"
            (str/trim (or err ""))))
    (let [account (get (json/parse-string out) "Account")
          region (some not-empty [(System/getenv "AWS_REGION")
                                  (System/getenv "AWS_DEFAULT_REGION")
                                  (str/trim (:out (sh "aws" "configure" "get" "region")))])]
      (when-not region
        (die! "no AWS region set. Run `aws configure set region <region>` or set AWS_REGION, then retry."))
      {:account account :region region})))

(defn- local-image-arch
  "The architecture Phase 2's `bb image` actually built, read from the
  local image itself (docker's own source of truth) rather than an env
  var -- so a deploy always matches whatever `bb image` last built,
  same principle as the Jolt version's ELF-header read, applied to a
  Docker image instead of a raw binary. Returns Lambda's spelling
  (\"x86_64\", not docker's \"amd64\")."
  []
  (let [{:keys [exit out err]} (sh "docker" "image" "inspect" local-image-tag
                                   "--format" "{{.Architecture}}")]
    (when-not (zero? exit)
      (die! "docker image inspect" local-image-tag "failed -- run `bb image` first.\n" err))
    (case (str/trim out)
      "amd64" "x86_64"
      "arm64" "arm64"
      (die! local-image-tag "has unexpected architecture" (str/trim out)))))

(def ^:private trust-policy
  (json/generate-string
   {:Version "2012-10-17"
    :Statement [{:Effect "Allow"
                 :Principal {:Service "lambda.amazonaws.com"}
                 :Action "sts:AssumeRole"}]}))

(defn- role-exists? []
  (zero? (:exit (sh "aws" "iam" "get-role" "--role-name" role-name))))

(defn- ensure-role! []
  (if (role-exists?)
    (println "lambda-mvp-jnk: role" role-name "already exists")
    (do
      (println "lambda-mvp-jnk: creating role" role-name)
      (let [{:keys [exit err]} (sh "aws" "iam" "create-role"
                                   "--role-name" role-name
                                   "--assume-role-policy-document" trust-policy)]
        (when-not (zero? exit) (die! "create-role failed:" err)))
      ;; IAM role propagation is eventually consistent -- a create-function
      ;; immediately after create-role can fail with "role cannot be assumed".
      (println "lambda-mvp-jnk: waiting 10s for IAM role propagation")
      (Thread/sleep 10000)))
  (let [{:keys [exit err]} (sh "aws" "iam" "attach-role-policy"
                               "--role-name" role-name
                               "--policy-arn" policy-arn)]
    (when-not (zero? exit) (die! "attach-role-policy failed:" err))))

(defn- role-arn []
  (-> (sh "aws" "iam" "get-role" "--role-name" role-name
          "--query" "Role.Arn" "--output" "text")
      :out str/trim))

(defn- ecr-repo-exists? []
  (zero? (:exit (sh "aws" "ecr" "describe-repositories" "--repository-names" repo-name))))

(defn- ensure-ecr-repo!
  "Create the ECR repo if missing, return its URI either way. Every
  container-image Lambda function needs an ECR repo -- there is no
  equivalent step in the zip-based Jolt version, this is genuinely new."
  []
  (when-not (ecr-repo-exists?)
    (println "lambda-mvp-jnk: creating ECR repository" repo-name)
    (let [{:keys [exit err]} (sh "aws" "ecr" "create-repository" "--repository-name" repo-name)]
      (when-not (zero? exit) (die! "ecr create-repository failed:" err))))
  (-> (sh "aws" "ecr" "describe-repositories" "--repository-names" repo-name
          "--query" "repositories[0].repositoryUri" "--output" "text")
      :out str/trim))

(defn- push-image! [ecr-uri region]
  (let [registry-host (first (str/split ecr-uri #"/"))
        {:keys [exit out err]} (sh "aws" "ecr" "get-login-password" "--region" region)]
    (when-not (zero? exit) (die! "ecr get-login-password failed:" err))
    (let [{:keys [exit err]} (p/shell {:continue true :in out :out :string :err :string}
                                      "docker" "login" "--username" "AWS" "--password-stdin" registry-host)]
      (when-not (zero? exit) (die! "docker login to" registry-host "failed:" err))))
  (let [tagged (str ecr-uri ":latest")
        {:keys [exit err]} (sh "docker" "tag" local-image-tag tagged)]
    (when-not (zero? exit) (die! "docker tag failed:" err)))
  (println "lambda-mvp-jnk: pushing" (str ecr-uri ":latest"))
  ;; Docker Desktop's containerd image store attaches a build-provenance
  ;; attestation to every image by default, so a plain `docker push` here
  ;; writes the tag as an OCI image INDEX (the real single-platform image
  ;; manifest plus a tiny attestation manifest) instead of a single image
  ;; manifest. AWS Lambda's CreateFunction rejects that index outright:
  ;; "The image manifest, config or layer media type for the source image
  ;; ... is not supported." -- confirmed against a real AWS account
  ;; during P3.T1 verification (see task-p3t1-report.md), not assumed.
  ;; `docker push --platform` is docker's own documented fix for this
  ;; exact case: it pushes only the single-platform manifest and drops
  ;; the index/attestation wrapper.
  (let [platform (case (local-image-arch) "x86_64" "linux/amd64" "arm64" "linux/arm64")
        {:keys [exit err]} (sh "docker" "push" "--platform" platform (str ecr-uri ":latest"))]
    (when-not (zero? exit) (die! "docker push failed:" err))))

(defn- function-exists? []
  (zero? (:exit (sh "aws" "lambda" "get-function" "--function-name" function-name))))

(defn- ensure-function! [image-uri]
  (if (function-exists?)
    (do
      (println "lambda-mvp-jnk: updating function code for" function-name (str "(" (local-image-arch) ")"))
      (let [{:keys [exit err]} (sh "aws" "lambda" "update-function-code"
                                   "--function-name" function-name
                                   "--image-uri" image-uri)]
        (when-not (zero? exit) (die! "update-function-code failed:" err)))
      (let [{:keys [exit err]} (sh "aws" "lambda" "wait" "function-updated" "--function-name" function-name)]
        (when-not (zero? exit) (die! "function did not reach Active state:" err)))
      (let [{:keys [exit err]} (sh "aws" "lambda" "update-function-configuration"
                                   "--function-name" function-name
                                   "--timeout" "15" "--memory-size" "2048")]
        (when-not (zero? exit) (die! "update-function-configuration failed:" err))))
    (do
      (println "lambda-mvp-jnk: creating function" function-name (str "(" (local-image-arch) ")"))
      (let [{:keys [exit err]} (sh "aws" "lambda" "create-function"
                                   "--function-name" function-name
                                   "--package-type" "Image"
                                   "--code" (str "ImageUri=" image-uri)
                                   "--architectures" (local-image-arch)
                                   "--role" (role-arn)
                                   "--timeout" "15" "--memory-size" "2048")]
        (when-not (zero? exit) (die! "create-function failed:" err)))))
  (let [{:keys [exit err]} (sh "aws" "lambda" "wait" "function-updated" "--function-name" function-name)]
    (when-not (zero? exit) (die! "function did not reach Active state:" err))))

(defn deploy! []
  (let [{:keys [region]} (require-aws-identity!)]
    (ensure-role!)
    (let [ecr-uri (ensure-ecr-repo!)]
      (push-image! ecr-uri region)
      (ensure-function! (str ecr-uri ":latest"))))
  (println "lambda-mvp-jnk: deployed" function-name "->"
           (-> (sh "aws" "lambda" "get-function" "--function-name" function-name
                   "--query" "Configuration.FunctionArn" "--output" "text")
               :out str/trim)))

(defn invoke! []
  (require-aws-identity!)
  (let [out-file (str (System/getProperty "java.io.tmpdir") "/lambda-mvp-jnk-invoke.json")
        {:keys [exit out err]}
        (sh "aws" "lambda" "invoke"
            "--function-name" function-name
            "--payload" "{}"
            "--cli-binary-format" "raw-in-base64-out"
            "--log-type" "Tail"
            "--query" "LogResult"
            "--output" "text"
            out-file)]
    (when-not (zero? exit) (die! "invoke failed:" err))
    (let [body (slurp out-file)
          log-tail (String. (.decode (java.util.Base64/getDecoder) (str/trim out)))
          parsed (try (json/parse-string body) (catch Exception _ nil))]
      (println "lambda-mvp-jnk: response body:")
      (println body)
      (println "lambda-mvp-jnk: log tail:")
      (println log-tail)
      (when (and (map? parsed) (get parsed "errorType"))
        (println "lambda-mvp-jnk: WARNING -- the function itself reported an error"
                 (str "(errorType: " (get parsed "errorType") ") -- see response body/log tail above."))))))

(defn teardown! []
  (require-aws-identity!)
  (when (function-exists?)
    (println "lambda-mvp-jnk: deleting function" function-name)
    (let [{:keys [exit err]} (sh "aws" "lambda" "delete-function" "--function-name" function-name)]
      (when-not (zero? exit) (die! "delete-function failed:" err))))
  (when (role-exists?)
    (println "lambda-mvp-jnk: detaching + deleting role" role-name)
    (let [{:keys [exit err]} (sh "aws" "iam" "detach-role-policy" "--role-name" role-name "--policy-arn" policy-arn)]
      (when-not (zero? exit) (die! "detach-role-policy failed:" err)))
    (let [{:keys [exit err]} (sh "aws" "iam" "delete-role" "--role-name" role-name)]
      (when-not (zero? exit) (die! "delete-role failed:" err))))
  (when (ecr-repo-exists?)
    (println "lambda-mvp-jnk: deleting ECR repository" repo-name)
    (let [{:keys [exit err]} (sh "aws" "ecr" "delete-repository" "--repository-name" repo-name "--force")]
      (when-not (zero? exit) (die! "ecr delete-repository failed:" err))))
  (println "lambda-mvp-jnk: teardown complete"))

(defn -main [& args]
  (case (first args)
    "deploy" (deploy!)
    "invoke" (invoke!)
    "teardown" (teardown!)
    (die! "usage: aws_lifecycle.clj deploy|invoke|teardown")))

(apply -main *command-line-args*)
