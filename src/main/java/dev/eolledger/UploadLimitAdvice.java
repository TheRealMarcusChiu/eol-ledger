package dev.eolledger;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/** Oversized uploads fail before any controller runs; send the operator back to the imports page with a reason. */
@ControllerAdvice
class UploadLimitAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    RedirectView tooLarge(RedirectAttributes flash) {
        flash.addFlashAttribute("warning", "That file is larger than the 10 MB upload limit. Nothing was changed.");
        RedirectView v = new RedirectView("/imports", true);
        v.setStatusCode(HttpStatus.SEE_OTHER);
        return v;
    }
}
