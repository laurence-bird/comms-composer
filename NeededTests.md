# Existing tests plus *Future Tests*


Composer when
  - the template does not exist should:
    - Send a FailedEvent
    - *Raise FailedFeedbackEvent*
    
  - some of the template data is missing should:
    - Send a FailedEvent
    - *Raise FailedFeedbackEvent*
    
  - rendering PDF succeeded should:
    - Store body in S3
    - Send a ComposedPrint
    
  - rendering PDF fails should:
    - Send a FailedEvent
    - *Raise FailedFeedbackEvent*
    
  - rendering SMS succeeded should:
    - Send a ComposedSMS
    - *Store SMS Sender in S3*
    - *Store SMS Body in S3*
    
  - rendering Email Succeeded should:
    - Send a ComposedEmail
    - Send a ComposedEmail with the body filled up correctly
    - *Store Email Sender in S3*
    - *Store Email Subject in S3*
    - *Store Email Body in S3*
    - *Store Email TextBody in S3*
    
  - HTTP endpoint is called when:
    - path is health check should:
      - return 200
    - path is render when:
      - template does not exists should
        - return 404 (NotFound)
      - some template data is missing should
        - return 400 (BadRequest)
      - docraptor is failing should:
        - return 503 ()Service Unavailable)
      - Everything is fine should:
        - return 200
        - return the PDF body as base64 encoded (bad idea)
     
