/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2015 The JRuby Team (jruby@jruby.org)
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.lexer.yacc;

import java.io.IOException;
import org.jcodings.Encoding;
import org.jruby.ast.RegexpNode;
import org.jruby.lexer.yacc.SyntaxException.PID;
import org.jruby.parser.RubyParser;
import org.jruby.util.ByteList;
import org.jruby.util.KCode;
import org.jruby.util.RegexpOptions;

import static org.jruby.lexer.LexingCommon.*;

public class StringTerm extends StrTerm {
    // Expand variables, Indentation of final marker
    private int flags;

    // Start of string ([, (, {, <, ', ", \n) 
    private final char begin;

    // End of string (], ), }, >, ', ", \0)
    private final char end;

    // Syntax errors (eof) will occur at this position.
    private final int startLine;

    // How many strings are nested in the current string term
    private int nest;

    public StringTerm(int flags, int begin, int end, int startLine) {
        this.flags = flags;
        this.begin = (char) begin;
        this.end   = (char) end;
        this.nest  = 0;
        this.startLine = startLine;
    }

    public int getFlags() {
        return flags;
    }

    protected ByteList createByteList(RubyLexer lexer) {
        ByteList bytelist = new ByteList(15);
        bytelist.setEncoding(lexer.getEncoding());
        return bytelist;
    }

    private int endFound(RubyLexer lexer) throws IOException {
            if ((flags & STR_FUNC_QWORDS) != 0) {
                flags = -1;
                lexer.getPosition();
                return ' ';
            }

            if ((flags & STR_FUNC_REGEXP) != 0) {
                RegexpOptions options = parseRegexpFlags(lexer);
                ByteList regexpBytelist = ByteList.create("");

                lexer.setValue(new RegexpNode(lexer.getPosition(), regexpBytelist, options));
                return RubyParser.tREGEXP_END;
            }

            lexer.setValue("" + end);
            return RubyParser.tSTRING_END;
    }

    public int parseString(RubyLexer lexer) throws IOException {
        boolean spaceSeen = false;
        int c;

        if (flags == -1) {
            lexer.setValue("" + end);
            return RubyParser.tSTRING_END;
        }

        c = lexer.nextc();
        if ((flags & STR_FUNC_QWORDS) != 0 && Character.isWhitespace(c)) {
            do { c = lexer.nextc(); } while (Character.isWhitespace(c));
            spaceSeen = true;
        }

        if (c == end && nest == 0) return endFound(lexer);

        if (spaceSeen) {
            lexer.pushback(c);
            lexer.getPosition();
            return ' ';
        }
        
        ByteList buffer = createByteList(lexer);
        lexer.newtok(true);
        if ((flags & STR_FUNC_EXPAND) != 0 && c == '#') {
            int token = lexer.peekVariableName(RubyParser.tSTRING_DVAR, RubyParser.tSTRING_DBEG);

            if (token != 0) {
                return token;
            } else {
                buffer.append(c);
            }
        } else {
            lexer.pushback(c);
        }

        Encoding enc[] = new Encoding[1];
        enc[0] = lexer.getEncoding();

        if (parseStringIntoBuffer(lexer, buffer, enc) == EOF) {
            lexer.setRubySourceline(startLine);
            lexer.compile_error("unterminated " + ((flags & STR_FUNC_REGEXP) != 0 ? "regexp" : "string") +  " meets end of file");
        }

        lexer.setValue(lexer.createStr(buffer, flags));
        return RubyParser.tSTRING_CONTENT;
    }

    private RegexpOptions parseRegexpFlags(RubyLexer lexer) throws IOException {
        RegexpOptions options = new RegexpOptions();
        int c;
        StringBuilder unknownFlags = new StringBuilder(10);

        lexer.newtok(true);
        for (c = lexer.nextc(); c != EOF
                && Character.isLetter(c); c = lexer.nextc()) {
            switch (c) {
            case 'i':
                options.setIgnorecase(true);
                break;
            case 'x':
                options.setExtended(true);
                break;
            case 'm':
                options.setMultiline(true);
                break;
            case 'o':
                options.setOnce(true);
                break;
            case 'n':
                options.setExplicitKCode(KCode.NONE);
                break;
            case 'e':
                options.setExplicitKCode(KCode.EUC);
                break;
            case 's':
                options.setExplicitKCode(KCode.SJIS);
                break;
            case 'u':
                options.setExplicitKCode(KCode.UTF8);
                break;
            case 'j':
                options.setJava(true);
                break;
            default:
                unknownFlags.append((char) c);
                break;
            }
        }
        lexer.pushback(c);
        if (unknownFlags.length() != 0) {
            lexer.compile_error(PID.REGEXP_UNKNOWN_OPTION, "unknown regexp option" +
                    (unknownFlags.length() > 1 ? "s" : "") + " - " + unknownFlags);
        }
        return options;
    }

    private void mixedEscape(RubyLexer lexer, Encoding foundEncoding, Encoding parserEncoding) {
        lexer.compile_error(PID.MIXED_ENCODING, "" + foundEncoding + " mixed within " + parserEncoding);
    }

    // mri: parser_tokadd_string
    public int parseStringIntoBuffer(RubyLexer lexer, ByteList buffer, Encoding enc[]) throws IOException {
        boolean qwords = (flags & STR_FUNC_QWORDS) != 0;
        boolean expand = (flags & STR_FUNC_EXPAND) != 0;
        boolean escape = (flags & STR_FUNC_ESCAPE) != 0;
        boolean regexp = (flags & STR_FUNC_REGEXP) != 0;
        boolean symbol = (flags & STR_FUNC_SYMBOL) != 0;
        boolean hasNonAscii = false;
        int c;

        while ((c = lexer.nextc()) != EOF) {
            if (lexer.getHeredocIndent() > 0) {
                lexer.update_heredoc_indent(c);
            }

            if (begin != '\0' && c == begin) {
                nest++;
            } else if (c == end) {
                if (nest == 0) {
                    lexer.pushback(c);
                    break;
                }
                nest--;
            } else if (expand && c == '#' && !lexer.peek('\n')) {
                int c2 = lexer.nextc();

                if (c2 == '$' || c2 == '@' || c2 == '{') {
                    lexer.pushback(c2);
                    lexer.pushback(c);
                    break;
                }
                lexer.pushback(c2);
            } else if (c == '\\') {
                c = lexer.nextc();
                switch (c) {
                case '\n':
                    if (qwords) break;
                    if (expand) continue;
                    buffer.append('\\');
                    break;

                case '\\':
                    if (escape) buffer.append(c);
                    break;

                case 'u':
                    if (!expand) {
                        buffer.append('\\');
                        break;
                    }

                    if (regexp) {
                        lexer.readUTFEscapeRegexpLiteral(buffer);
                    } else {
                        lexer.readUTFEscape(buffer, true, symbol);
                    }

                    if (hasNonAscii && buffer.getEncoding() != enc[0]) {
                        mixedEscape(lexer, buffer.getEncoding(), enc[0]);
                    }

                    continue;
                default:
                    if (c == EOF) return EOF;

                    if (!lexer.isASCII(c)) {
                        if (!expand) buffer.append('\\');

                        // goto non_ascii
                        hasNonAscii = true;

                        if (buffer.getEncoding() != enc[0]) {
                            mixedEscape(lexer, buffer.getEncoding(), enc[0]);
                            continue;
                        }

                        if (!lexer.tokadd_mbchar(c, buffer)) {
                            lexer.compile_error(PID.INVALID_MULTIBYTE_CHAR, "invalid multibyte char (" + enc[0] + ")");
                        }

                        continue;
                        // end of goto non_ascii
                    }

                    if (regexp) {
                        if (c == end && !simple_re_meta(c)) {
                            buffer.append(c);
                            continue;
                        }
                        lexer.pushback(c);
                        parseEscapeIntoBuffer(lexer, buffer);

                        if (hasNonAscii && buffer.getEncoding() != enc[0]) {
                            mixedEscape(lexer, buffer.getEncoding(), enc[0]);
                        }
                        
                        continue;
                    } else if (expand) {
                        lexer.pushback(c);
                        if (escape) buffer.append('\\');
                        c = lexer.readEscape();
                    } else if (qwords && Character.isWhitespace(c)) {
                        /* ignore backslashed spaces in %w */
                    } else if (c != end && !(begin != '\0' && c == begin)) {
                        buffer.append('\\');
                        lexer.pushback(c);;
                        continue;
                    }
                }
            } else if (!lexer.isASCII(c)) {
nonascii:       hasNonAscii = true; // Label for comparison with MRI only.

                if (buffer.getEncoding() != enc[0]) {
                    mixedEscape(lexer, buffer.getEncoding(), enc[0]);
                    continue;
                }

                if (!lexer.tokadd_mbchar(c, buffer)) {
                    lexer.compile_error(PID.INVALID_MULTIBYTE_CHAR, "invalid multibyte char (" + enc[0] + ")");
                }

                continue;
                // end of goto non_ascii
            } else if (qwords && Character.isWhitespace(c)) {
                lexer.pushback(c);
                break;
            }

            if ((c & 0x80) != 0) {
                hasNonAscii = true;
                if (buffer.getEncoding() != enc[0]) {
                    mixedEscape(lexer, buffer.getEncoding(), enc[0]);
                    continue;
                }
            }
            buffer.append(c);
        }

        enc[0] = buffer.getEncoding();
        
        return c;
    }

    private boolean simple_re_meta(int c) {
        switch(c) {
            case '$': case '*': case '+': case '.': case '?': case '^': case '|': case ')': case ']': case '}': case '>':
                return true;
        }

        return false;
    }    

    // Was a goto in original ruby lexer
    private void escaped(RubyLexer lexer, ByteList buffer) throws java.io.IOException {
        int c;

        switch (c = lexer.nextc()) {
        case '\\':
            parseEscapeIntoBuffer(lexer, buffer);
            break;
        case EOF:
            lexer.compile_error("Invalid escape character syntax");
        default:
            buffer.append(c);
        }
    }

    private void parseEscapeIntoBuffer(RubyLexer lexer, ByteList buffer) throws java.io.IOException {
        int c;

        switch (c = lexer.nextc()) {
        case '\n':
            break; /* just ignore */
        case '0':
        case '1':
        case '2':
        case '3': /* octal constant */
        case '4':
        case '5':
        case '6':
        case '7':
            buffer.append('\\');
            buffer.append(c);
            for (int i = 0; i < 2; i++) {
                c = lexer.nextc();
                if (c == EOF) {
                    lexer.compile_error("Invalid escape character syntax");
                }
                if (!isOctChar(c)) {
                    lexer.pushback(c);
                    break;
                }
                buffer.append(c);
            }
            break;
        case 'x': /* hex constant */
            buffer.append('\\');
            buffer.append(c);
            c = lexer.nextc();
            if (!isHexChar(c)) {
                lexer.compile_error("Invalid escape character syntax");
            }
            buffer.append(c);
            c = lexer.nextc();
            if (isHexChar(c)) {
                buffer.append(c);
            } else {
                lexer.pushback(c);
            }
            break;
        case 'M':
            if ((lexer.nextc()) != '-') {
                lexer.compile_error("Invalid escape character syntax");
            }
            buffer.append(new byte[] { '\\', 'M', '-' });
            escaped(lexer, buffer);
            break;
        case 'C':
            if ((lexer.nextc()) != '-') {
                lexer.compile_error("Invalid escape character syntax");
            }
            buffer.append(new byte[] { '\\', 'C', '-' });
            escaped(lexer, buffer);
            break;
        case 'c':
            buffer.append(new byte[] { '\\', 'c' });
            escaped(lexer, buffer);
            break;
        case EOF:
            lexer.compile_error("Invalid escape character syntax");
        default:
            if (c != '\\' || c != end) buffer.append('\\');

            buffer.append(c);
        }
    }
}
